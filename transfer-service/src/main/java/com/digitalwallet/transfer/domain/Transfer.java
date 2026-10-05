package com.digitalwallet.transfer.domain;

import com.digitalwallet.common.money.Money;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * One user's request to send money to another, and what became of it.
 *
 * <p>This entity holds no balance and performs no arithmetic on money. It records intent and
 * outcome; wallet-service owns the value itself. Its own id doubles as the posting's
 * {@code externalRef}, which is unique over there — that single fact is what makes retrying a
 * posting safe.
 *
 * <p>Status changes go through {@link #complete()}, {@link #fail(FailureReason)} and
 * {@link #markUnresolved()} rather than a setter, so every transition is checked against
 * {@link TransferStatus#canTransitionTo}. A settled transfer cannot be reopened by a late retry.
 */
@Entity
@Table(name = "transfer")
public class Transfer {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "sender_user_id", nullable = false, updatable = false)
    private UUID senderUserId;

    @Column(name = "recipient_user_id", nullable = false, updatable = false)
    private UUID recipientUserId;

    @Column(name = "amount_minor", nullable = false, updatable = false)
    private long amountMinor;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private TransferStatus status;

    @Enumerated(EnumType.STRING)
    @Column(name = "failure_reason", length = 120)
    private FailureReason failureReason;

    @Column(name = "note", length = 140, updatable = false)
    private String note;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    protected Transfer() {
        // for JPA
    }

    private Transfer(UUID id, UUID senderUserId, UUID recipientUserId, long amountMinor, String note) {
        this.id = id;
        this.senderUserId = senderUserId;
        this.recipientUserId = recipientUserId;
        this.amountMinor = amountMinor;
        this.note = note;
        this.status = TransferStatus.PENDING;
        this.createdAt = Instant.now();
    }

    /**
     * Opens a transfer in {@link TransferStatus#PENDING}. Nothing has moved at this point — the
     * row exists so that if the process dies in the next millisecond there is still a record that
     * this request was accepted.
     */
    public static Transfer open(UUID senderUserId, UUID recipientUserId, Money amount, String note) {
        if (senderUserId.equals(recipientUserId)) {
            throw new IllegalArgumentException("A transfer must have two different parties");
        }
        if (!amount.isPositive()) {
            throw new IllegalArgumentException("A transfer amount must be positive");
        }
        return new Transfer(UUID.randomUUID(), senderUserId, recipientUserId, amount.minor(), note);
    }

    /** wallet-service confirmed the posting. */
    public void complete() {
        transitionTo(TransferStatus.COMPLETED);
        this.failureReason = null;
        this.completedAt = Instant.now();
    }

    /** wallet-service refused, for a reason worth telling the sender. */
    public void fail(FailureReason reason) {
        if (reason == null) {
            throw new IllegalArgumentException("A failed transfer must carry a reason");
        }
        transitionTo(TransferStatus.FAILED);
        this.failureReason = reason;
        this.completedAt = Instant.now();
    }

    /**
     * The posting's outcome is unknown — a timeout, or a downstream error that could have landed
     * either side of the commit. Left for the reconciliation sweep to settle.
     */
    public void markUnresolved() {
        transitionTo(TransferStatus.NEEDS_RECONCILIATION);
    }

    private void transitionTo(TransferStatus next) {
        if (!status.canTransitionTo(next)) {
            throw new IllegalStateException(
                    "Transfer %s cannot move from %s to %s".formatted(id, status, next));
        }
        this.status = next;
    }

    /** True when the caller may still see a different answer later. */
    public boolean isUnresolved() {
        return status == TransferStatus.NEEDS_RECONCILIATION;
    }

    public Money amount() {
        return Money.ofMinor(amountMinor);
    }

    public boolean isOwnedBy(UUID userId) {
        return senderUserId.equals(userId);
    }

    public UUID getId() {
        return id;
    }

    public UUID getSenderUserId() {
        return senderUserId;
    }

    public UUID getRecipientUserId() {
        return recipientUserId;
    }

    public long getAmountMinor() {
        return amountMinor;
    }

    public TransferStatus getStatus() {
        return status;
    }

    public FailureReason getFailureReason() {
        return failureReason;
    }

    public String getNote() {
        return note;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getCompletedAt() {
        return completedAt;
    }
}
