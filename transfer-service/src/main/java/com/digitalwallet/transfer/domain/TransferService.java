package com.digitalwallet.transfer.domain;

import com.digitalwallet.common.error.ApiException;
import com.digitalwallet.common.error.ErrorCode;
import com.digitalwallet.common.error.ProblemDetails;
import com.digitalwallet.common.money.Money;
import com.digitalwallet.transfer.api.dto.TransferDtos.SendTransferRequest;
import com.digitalwallet.transfer.api.dto.TransferDtos.TransferPageResponse;
import com.digitalwallet.transfer.api.dto.TransferDtos.TransferResponse;
import com.digitalwallet.transfer.client.PostingOutcome;
import com.digitalwallet.transfer.client.UserDirectoryClient;
import com.digitalwallet.transfer.client.WalletPostingClient;
import com.digitalwallet.transfer.config.TransferProperties;
import com.digitalwallet.transfer.idempotency.IdempotencyService;
import com.digitalwallet.transfer.idempotency.IdempotencyService.Claim;
import com.digitalwallet.transfer.idempotency.RequestHash;
import com.digitalwallet.transfer.security.CurrentUserProvider;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Orchestrates a transfer: intent here, money over in wallet-service.
 *
 * <p>Deliberately <b>not</b> {@code @Transactional} at the top level. The flow makes a network call
 * in the middle, and holding a database transaction open across it would tie up a connection for the
 * length of someone else's outage and, worse, would mean the transfer row recording "I asked" only
 * becomes visible once the answer is already known, which defeats the point of writing it first.
 * Each step commits on its own instead.
 *
 * <p>The order of operations is the design:
 * <ol>
 *   <li>Everything that can be rejected without side effects is checked first, so a bad request
 *       never consumes an idempotency key.</li>
 *   <li>The key is claimed, which is what makes concurrent duplicates collide.</li>
 *   <li>The transfer is written as PENDING <em>before</em> the posting call, so a crash mid-call
 *       still leaves evidence the request was accepted.</li>
 *   <li>The posting is attempted, and its three possible answers are handled separately,
 *       including the one that means "I do not know".</li>
 * </ol>
 */
@Service
public class TransferService {

    private static final Logger log = LoggerFactory.getLogger(TransferService.class);

    public static final int MAX_PAGE_SIZE = 100;

    private final TransferRepository transferRepository;
    private final IdempotencyService idempotencyService;
    private final UserDirectoryClient userDirectory;
    private final WalletPostingClient walletClient;
    private final CurrentUserProvider currentUser;
    private final ObjectMapper objectMapper;
    private final long maxAmountMinor;

    public TransferService(TransferRepository transferRepository,
                           IdempotencyService idempotencyService,
                           UserDirectoryClient userDirectory,
                           WalletPostingClient walletClient,
                           CurrentUserProvider currentUser,
                           ObjectMapper objectMapper,
                           TransferProperties properties) {
        this.transferRepository = transferRepository;
        this.idempotencyService = idempotencyService;
        this.userDirectory = userDirectory;
        this.walletClient = walletClient;
        this.currentUser = currentUser;
        this.objectMapper = objectMapper;
        this.maxAmountMinor = properties.maxAmountMinor();
    }

    /**
     * The HTTP answer, already serialized.
     *
     * <p>Carrying the body as JSON text rather than an object is what makes a replay a replay: the
     * retry returns the answer the original request produced (the same transfer id, the same
     * balance, the same timestamps) rather than a freshly computed one that might have drifted.
     *
     * <p>Note that {@code response_body} is a {@code jsonb} column, and PostgreSQL normalises jsonb:
     * it reorders object keys and drops insignificant whitespace. So the replayed bytes are not
     * identical to the original bytes, though every field and value is. Key order carries no meaning
     * in JSON and no correct client can observe the difference, and jsonb buys validation and
     * queryability in exchange, but it is worth knowing the guarantee is "the same response", not
     * "the same bytes".
     */
    public record SendOutcome(int status, String body) {
    }

    public SendOutcome send(String idempotencyKey, SendTransferRequest request) {
        UUID senderUserId = currentUser.requireCurrentUserId();
        String token = currentUser.requireCurrentToken();
        Money amount = requireSendableAmount(request.amountMinor());

        // Resolved before the key is claimed. A request naming a handle that does not exist has no
        // side effects to protect, so it should fail the same way every time it is sent rather than
        // burning the caller's key on an answer that will never change.
        Party recipient = resolveRecipient(request.recipientHandle(), senderUserId, token);
        Party sender = userDirectory.requireCaller(senderUserId, token);

        String requestHash = RequestHash.of(request.recipientHandle(), amount.minor(), request.note());
        Claim claim = idempotencyService.claim(idempotencyKey, senderUserId, requestHash);

        if (claim instanceof Claim.Replay replay) {
            log.debug("Replaying transfer response for a repeated idempotency key");
            return new SendOutcome(replay.status(), replay.body());
        }

        UUID recordId = ((Claim.Granted) claim).recordId();
        Transfer transfer;
        try {
            transfer = transferRepository.saveAndFlush(
                    Transfer.open(sender, recipient, amount, request.note()));
        } catch (RuntimeException couldNotOpen) {
            // No transfer exists, so there is nothing for a retry to duplicate. Hand the key back
            // rather than leaving it claimed and unanswerable forever.
            idempotencyService.release(recordId);
            throw couldNotOpen;
        }

        return carryOut(transfer, recordId);
    }

    /**
     * Attempts the posting and settles the transfer according to what came back.
     *
     * <p>Every branch ends with the idempotency record answered, because an unanswered key makes
     * the client's next retry wait for something that will never arrive.
     */
    private SendOutcome carryOut(Transfer transfer, UUID recordId) {
        PostingOutcome outcome = walletClient.post(transfer);

        SendOutcome answer = switch (outcome) {
            case PostingOutcome.Posted posted -> {
                transfer.complete();
                save(transfer);
                log.info("Transfer {} completed against journal entry {}",
                        transfer.getId(), posted.journalEntryId());
                yield json(HttpStatus.CREATED.value(),
                        TransferResponse.from(transfer, posted.senderBalanceAfterMinor()));
            }

            case PostingOutcome.Rejected rejected -> {
                transfer.fail(rejected.reason());
                save(transfer);
                log.info("Transfer {} failed: {}", transfer.getId(), rejected.reason());
                yield problem(rejected.reason().errorCode(), rejected.detail());
            }

            // The posting may or may not have committed. Saying "failed" could lose a payment that
            // actually went through, and saying "succeeded" could invent one that did not. The
            // honest answer is 202: recorded, outcome pending, ask again by id.
            case PostingOutcome.Unknown unknown -> {
                transfer.markUnresolved();
                save(transfer);
                log.warn("Transfer {} is unresolved and left for the sweep: {}",
                        transfer.getId(), unknown.cause());
                yield json(HttpStatus.ACCEPTED.value(), TransferResponse.from(transfer));
            }
        };

        idempotencyService.answer(recordId, answer.status(), answer.body(), transfer.getId());
        return answer;
    }

    @Transactional(readOnly = true)
    public TransferResponse requireOwnTransfer(UUID transferId, UUID callerUserId) {
        Transfer transfer = transferRepository.findById(transferId)
                .orElseThrow(() -> new ApiException(ErrorCode.ACCOUNT_NOT_FOUND, "No such transfer"));

        // Ownership is checked against the token subject, never against anything the caller sent.
        // Skipping this is the single most common real-world API vulnerability.
        //
        // The answer for "someone else's transfer" is deliberately the same 404 as "no such
        // transfer": a 403 would confirm that the id exists, letting a caller map out other
        // people's activity by probing ids.
        if (!transfer.isOwnedBy(callerUserId)) {
            log.warn("User {} tried to read transfer {} they do not own", callerUserId, transferId);
            throw new ApiException(ErrorCode.ACCOUNT_NOT_FOUND, "No such transfer");
        }
        return TransferResponse.from(transfer);
    }

    @Transactional(readOnly = true)
    public TransferPageResponse historyFor(UUID userId, String encodedCursor, int requestedSize) {
        int size = Math.clamp(requestedSize, 1, MAX_PAGE_SIZE);

        // One more row than asked for. If it comes back there is another page, which answers
        // "is there more?" without a second COUNT query.
        Pageable limit = PageRequest.ofSize(size + 1);

        List<Transfer> rows;
        if (encodedCursor == null || encodedCursor.isBlank()) {
            rows = transferRepository.findFirstPageForSender(userId, limit);
        } else {
            TransferCursor cursor = TransferCursor.decode(encodedCursor);
            rows = transferRepository.findNextPageForSender(
                    userId, cursor.createdAt(), cursor.transferId(), limit);
        }

        boolean hasMore = rows.size() > size;
        List<Transfer> pageRows = hasMore ? rows.subList(0, size) : rows;

        List<TransferResponse> responses = new ArrayList<>(pageRows.size());
        for (Transfer transfer : pageRows) {
            responses.add(TransferResponse.from(transfer));
        }

        String nextCursor = hasMore && !pageRows.isEmpty()
                ? TransferCursor.from(pageRows.get(pageRows.size() - 1)).encode()
                : null;

        return new TransferPageResponse(List.copyOf(responses), nextCursor);
    }

    @Transactional
    public void save(Transfer transfer) {
        transferRepository.saveAndFlush(transfer);
    }

    private Money requireSendableAmount(long amountMinor) {
        if (amountMinor > maxAmountMinor) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED,
                    "amountMinor exceeds the per-transfer ceiling of " + maxAmountMinor);
        }
        return Money.positive(amountMinor);
    }

    private Party resolveRecipient(String handle, UUID senderUserId, String token) {
        Party recipient = userDirectory.findByHandle(RequestHash.canonicalHandle(handle), token)
                .orElseThrow(() -> new ApiException(ErrorCode.ACCOUNT_NOT_FOUND, "No user with that handle"));

        if (recipient.userId().equals(senderUserId)) {
            // Would net to zero while still writing two ledger lines and a transfer row.
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "You cannot send money to yourself");
        }
        return recipient;
    }

    private SendOutcome json(int status, TransferResponse body) {
        return new SendOutcome(status, serialize(body));
    }

    private SendOutcome problem(ErrorCode code, String detail) {
        return new SendOutcome(code.status().value(),
                serialize(ProblemDetails.of(code, detail, "/api/transfers")));
    }

    private String serialize(Object body) {
        try {
            return objectMapper.writeValueAsString(body);
        } catch (JacksonException e) {
            throw new IllegalStateException("Could not serialize a transfer response", e);
        }
    }
}
