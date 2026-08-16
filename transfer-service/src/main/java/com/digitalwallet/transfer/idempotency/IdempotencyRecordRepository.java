package com.digitalwallet.transfer.idempotency;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface IdempotencyRecordRepository extends JpaRepository<IdempotencyRecord, UUID> {

    /**
     * Keys are scoped per user, so this always looks up both. Searching by key alone would let one
     * user's response be handed to another who happened to pick the same string.
     */
    Optional<IdempotencyRecord> findByIdempotencyKeyAndUserId(String idempotencyKey, UUID userId);
}
