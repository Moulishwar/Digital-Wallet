-- Transfer schema: the intent to move money, and the record of what a given request answered.
--
-- Note what is absent: there is no balance and no ledger here. This service records that a user
-- asked to send money and how that request turned out; wallet-service is the only place a balance
-- exists. Keeping the two apart is what lets the money movement itself stay a single local
-- transaction (DESIGN.md section 4.2).

CREATE TABLE transfer
(
    -- Also sent to wallet-service as the posting's external_ref, where it is UNIQUE. That is what
    -- makes retrying a posting safe: the second attempt cannot duplicate the first.
    id                 UUID PRIMARY KEY,

    sender_user_id     UUID         NOT NULL,
    recipient_user_id  UUID         NOT NULL,

    amount_minor       BIGINT       NOT NULL,

    status             VARCHAR(20)  NOT NULL,

    -- Set only on FAILED. A short machine-readable code, not a sentence.
    failure_reason     VARCHAR(120),

    note               VARCHAR(140),

    created_at         TIMESTAMPTZ  NOT NULL DEFAULT now(),
    completed_at       TIMESTAMPTZ,

    CONSTRAINT transfer_amount_chk CHECK (amount_minor > 0),

    -- Sending to yourself would net to zero while still writing two ledger lines. Rejected at the
    -- edge, and again here so no code path can slip one in.
    CONSTRAINT transfer_distinct_parties_chk CHECK (sender_user_id <> recipient_user_id),

    CONSTRAINT transfer_status_chk CHECK (
        status IN ('PENDING', 'COMPLETED', 'FAILED', 'NEEDS_RECONCILIATION')
        ),

    -- A reason without a failure, or a failure without a reason, means the state machine has a
    -- hole in it. Cheap to assert, and it turns a subtle bug into a loud one.
    CONSTRAINT transfer_failure_reason_chk CHECK (
        (status = 'FAILED' AND failure_reason IS NOT NULL)
            OR (status <> 'FAILED' AND failure_reason IS NULL)
        )
);

-- The sender's own history, newest first — the access pattern behind GET /api/transfers.
CREATE INDEX transfer_sender_idx ON transfer (sender_user_id, created_at DESC, id DESC);

-- The reconciliation sweep's query: unresolved transfers, oldest first.
CREATE INDEX transfer_unresolved_idx ON transfer (status, created_at) WHERE status = 'NEEDS_RECONCILIATION';


CREATE TABLE idempotency_record
(
    id              UUID PRIMARY KEY,

    idempotency_key VARCHAR(64)  NOT NULL,
    user_id         UUID         NOT NULL,

    -- SHA-256 of the canonicalised request. Replaying a key with a different body is a client bug
    -- worth reporting rather than silently answering, so the hash is stored to detect it.
    --
    -- VARCHAR, never CHAR: CHAR(64) is blank-padded, so a hash read back would not equal the one
    -- written. This exact mistake has already been made twice in this project.
    request_hash    VARCHAR(64)  NOT NULL,

    -- NULL until the request finishes. The row is claimed first and answered second, so that two
    -- concurrent requests with the same key race at the unique index below rather than both doing
    -- the work. NULL therefore means "claimed, still in flight", which is a real state a caller
    -- can arrive in — not a placeholder value pretending to be an answer.
    response_status INT,
    response_body   JSONB,

    -- The transfer this key produced. Null while in flight, and null forever for a request that
    -- was rejected before any transfer existed.
    transfer_id     UUID REFERENCES transfer (id),

    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),

    -- Half an answer is not a state this system has. Either the response is recorded or it is not.
    CONSTRAINT idempotency_record_response_chk CHECK (
        (response_status IS NULL) = (response_body IS NULL)
        ),

    -- Scoped per user, so one user's choice of key can neither collide with nor probe another's.
    -- Being a UNIQUE constraint rather than an application check is the point: two concurrent
    -- requests with the same key race at the database, and exactly one wins. A check that read
    -- first and inserted second would have a window between the two where both could pass.
    CONSTRAINT idempotency_record_key_uk UNIQUE (idempotency_key, user_id)
);
