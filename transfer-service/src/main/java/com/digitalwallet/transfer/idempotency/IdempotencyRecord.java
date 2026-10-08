package com.digitalwallet.transfer.idempotency;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * What a given {@code Idempotency-Key} answered, so replaying the key replays the answer.
 *
 * <p>The row is written in two steps. It is <em>claimed</em> before any work happens, which is what
 * makes concurrent duplicates race at the unique index instead of both going through; and it is
 * <em>answered</em> once the outcome is known. Between those two points {@code responseStatus} is
 * null, and that gap is a genuine state: another request holding the same key is still in flight.
 *
 * <p>The request hash is stored so that reusing a key with a <em>different</em> body can be
 * reported rather than silently answered with someone else's result. That is nearly always a client
 * bug, and quietly returning the wrong receipt would hide it.
 */
@Entity
@Table(name = "idempotency_record")
public class IdempotencyRecord {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "idempotency_key", nullable = false, length = 64, updatable = false)
    private String idempotencyKey;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Column(name = "request_hash", nullable = false, length = 64, updatable = false)
    private String requestHash;

    @Column(name = "response_status")
    private Integer responseStatus;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "response_body")
    private String responseBody;

    @Column(name = "transfer_id")
    private UUID transferId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected IdempotencyRecord() {
        // for JPA
    }

    private IdempotencyRecord(String idempotencyKey, UUID userId, String requestHash) {
        this.id = UUID.randomUUID();
        this.idempotencyKey = idempotencyKey;
        this.userId = userId;
        this.requestHash = requestHash;
        this.createdAt = Instant.now();
    }

    /** Stakes the key before the work begins. The answer is filled in later. */
    public static IdempotencyRecord claim(String idempotencyKey, UUID userId, String requestHash) {
        return new IdempotencyRecord(idempotencyKey, userId, requestHash);
    }

    /**
     * Records the outcome. Called once; a key that already has an answer is never re-answered,
     * because that is precisely the guarantee the key exists to provide.
     */
    public void answer(int status, String body, UUID transferId) {
        if (isAnswered()) {
            throw new IllegalStateException("Idempotency record " + id + " already has a response");
        }
        this.responseStatus = status;
        this.responseBody = body;
        this.transferId = transferId;
    }

    public boolean isAnswered() {
        return responseStatus != null;
    }

    public boolean matches(String candidateRequestHash) {
        return requestHash.equals(candidateRequestHash);
    }

    public UUID getId() {
        return id;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public UUID getUserId() {
        return userId;
    }

    public String getRequestHash() {
        return requestHash;
    }

    public Integer getResponseStatus() {
        return responseStatus;
    }

    public String getResponseBody() {
        return responseBody;
    }

    public UUID getTransferId() {
        return transferId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
