package com.digitalwallet.transfer.idempotency;

import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The transactional edge of idempotency handling.
 *
 * <p>Every method here runs in its own committed transaction, and that is the whole reason this is
 * a separate bean rather than a few private methods on {@link IdempotencyService}. Two things
 * depend on it:
 *
 * <ul>
 *   <li>The claim must be <em>visible to other requests</em> the moment it is made, so a concurrent
 *       duplicate collides with it rather than proceeding in parallel.</li>
 *   <li>A request waiting for another to finish must see each fresh read, not a cached entity from
 *       its own persistence context. A new transaction per read is what makes the wait terminate.</li>
 * </ul>
 *
 * <p>Spring's transaction proxying only applies to calls that arrive from outside the bean, so
 * keeping these on their own object is what makes the annotations take effect at all.
 */
@Component
public class IdempotencyStore {

    private final IdempotencyRecordRepository repository;

    public IdempotencyStore(IdempotencyRecordRepository repository) {
        this.repository = repository;
    }

    /**
     * Stakes the key.
     *
     * <p>Throws {@link org.springframework.dao.DataIntegrityViolationException} if another request
     * already holds it. That collision is the mechanism, not an error case: it is decided by the
     * unique index, so there is no window between checking and inserting in which two callers could
     * both believe they won.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public IdempotencyRecord insertClaim(String idempotencyKey, UUID userId, String requestHash) {
        return repository.saveAndFlush(IdempotencyRecord.claim(idempotencyKey, userId, requestHash));
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    public Optional<IdempotencyRecord> find(String idempotencyKey, UUID userId) {
        return repository.findByIdempotencyKeyAndUserId(idempotencyKey, userId);
    }

    /** Fills in the outcome, making it available to every later replay of this key. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void answer(UUID recordId, int status, String body, UUID transferId) {
        IdempotencyRecord record = repository.findById(recordId)
                .orElseThrow(() -> new IllegalStateException("Idempotency record vanished: " + recordId));
        record.answer(status, body, transferId);
        repository.saveAndFlush(record);
    }

    /**
     * Gives up a claim whose work could not be started.
     *
     * <p>Without this, a request that failed before it could produce any answer would leave the key
     * permanently claimed and unanswerable, so the caller's retry — the exact thing the key exists
     * to support — would hang and then be refused.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void release(UUID recordId) {
        repository.deleteById(recordId);
    }
}
