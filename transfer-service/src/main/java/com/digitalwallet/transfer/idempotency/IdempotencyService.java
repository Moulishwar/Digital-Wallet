package com.digitalwallet.transfer.idempotency;

import com.digitalwallet.common.error.ApiException;
import com.digitalwallet.common.error.ErrorCode;
import com.digitalwallet.transfer.config.TransferProperties;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

/**
 * Makes {@code POST /api/transfers} safe to retry.
 *
 * <p>The rule the whole class serves: <em>one key, one outcome</em>. A client that retries after a
 * timeout must receive the answer its first request produced, not a second transfer.
 *
 * <p>Three cases arise, and only the third is subtle:
 *
 * <ol>
 *   <li><b>New key</b>: claimed, and the caller does the work.</li>
 *   <li><b>Known key, already answered</b>: the stored response is replayed verbatim.</li>
 *   <li><b>Known key, not yet answered</b>: another request holds it and is still working. This
 *       one waits briefly for that answer rather than either duplicating the work or immediately
 *       refusing, because in the common case (a client firing retries at a request that is about to
 *       succeed) waiting a few milliseconds returns the right answer instead of an error.</li>
 * </ol>
 *
 * <p>The wait is bounded. If the holder never answers (its process died mid-request), waiting
 * forever would turn one lost request into a hung one for every retry after it, so past the bound
 * the caller is told plainly that the key is in flight and can try again.
 */
@Service
public class IdempotencyService {

    private static final Logger log = LoggerFactory.getLogger(IdempotencyService.class);

    /** How often to re-check while waiting on another request's answer. */
    private static final Duration POLL_INTERVAL = Duration.ofMillis(50);

    private final IdempotencyStore store;
    private final Duration maxWait;

    public IdempotencyService(IdempotencyStore store, TransferProperties properties) {
        this.store = store;
        this.maxWait = properties.idempotency().maxWait();
    }

    /**
     * The outcome of trying to take ownership of a key: either this request does the work, or an
     * earlier one already did and its answer is handed back.
     */
    public sealed interface Claim {

        /** This request owns the key. Do the work, then call {@link #answer}. */
        record Granted(UUID recordId) implements Claim {
        }

        /** An earlier request with the same key already answered. Return this, unchanged. */
        record Replay(int status, String body) implements Claim {
        }
    }

    /**
     * @throws ApiException with {@link ErrorCode#IDEMPOTENCY_CONFLICT} if the key was used before
     *                      with a different request body, or
     *                      {@link ErrorCode#IDEMPOTENCY_IN_PROGRESS} if the holder does not answer
     *                      within the configured wait
     */
    public Claim claim(String idempotencyKey, UUID userId, String requestHash) {
        Optional<IdempotencyRecord> existing = store.find(idempotencyKey, userId);
        if (existing.isPresent()) {
            return resolve(existing.get(), idempotencyKey, userId, requestHash);
        }

        try {
            IdempotencyRecord claimed = store.insertClaim(idempotencyKey, userId, requestHash);
            return new Claim.Granted(claimed.getId());
        } catch (DataIntegrityViolationException raced) {
            // Someone inserted the same key between our read and our write. The unique index
            // settled it; whoever won owns the outcome, and we adopt their answer.
            log.debug("Lost the race for idempotency key held by user {}", userId);
            IdempotencyRecord winner = store.find(idempotencyKey, userId).orElseThrow(() -> raced);
            return resolve(winner, idempotencyKey, userId, requestHash);
        }
    }

    /** Records the outcome against the claimed key. */
    public void answer(UUID recordId, int status, String body, UUID transferId) {
        store.answer(recordId, status, body, transferId);
    }

    /** Abandons a claim that produced no answer, so the caller's retry is not locked out. */
    public void release(UUID recordId) {
        store.release(recordId);
    }

    private Claim resolve(IdempotencyRecord record, String idempotencyKey, UUID userId, String requestHash) {
        requireSameRequest(record, requestHash);

        if (record.isAnswered()) {
            return new Claim.Replay(record.getResponseStatus(), record.getResponseBody());
        }
        return waitForAnswer(idempotencyKey, userId, requestHash);
    }

    /**
     * Polls until the holder of the key records its answer, or the bound elapses.
     *
     * <p>Each read goes through {@link IdempotencyStore}, which opens a new transaction, so the
     * loop actually sees the other request's commit. Re-reading inside one transaction would return
     * the same cached row forever and never terminate.
     */
    private Claim waitForAnswer(String idempotencyKey, UUID userId, String requestHash) {
        long deadline = System.nanoTime() + maxWait.toNanos();

        while (System.nanoTime() < deadline) {
            try {
                Thread.sleep(POLL_INTERVAL.toMillis());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new ApiException(ErrorCode.IDEMPOTENCY_IN_PROGRESS,
                        "Interrupted while waiting for the original request to finish", e);
            }

            Optional<IdempotencyRecord> latest = store.find(idempotencyKey, userId);
            if (latest.isEmpty()) {
                // The holder released its claim without answering. Take it over, but if yet
                // another request gets there first, keep waiting on that one. Calling back into
                // claim() here would recurse, and each level would start its own fresh deadline.
                try {
                    return new Claim.Granted(
                            store.insertClaim(idempotencyKey, userId, requestHash).getId());
                } catch (DataIntegrityViolationException anotherRequestTookIt) {
                    continue;
                }
            }
            IdempotencyRecord record = latest.get();
            requireSameRequest(record, requestHash);
            if (record.isAnswered()) {
                return new Claim.Replay(record.getResponseStatus(), record.getResponseBody());
            }
        }

        log.warn("Idempotency key for user {} still unanswered after {}", userId, maxWait);
        throw new ApiException(ErrorCode.IDEMPOTENCY_IN_PROGRESS,
                "An earlier request with this Idempotency-Key has not finished yet. Retry shortly.");
    }

    private void requireSameRequest(IdempotencyRecord record, String requestHash) {
        if (!record.matches(requestHash)) {
            // Answering this would hand the caller a receipt for a payment they did not ask for.
            throw new ApiException(ErrorCode.IDEMPOTENCY_CONFLICT,
                    "This Idempotency-Key was already used for a different request");
        }
    }
}
