-- Ledger schema: accounts, journal entries and the lines that make up an entry.
--
-- The model is double-entry. Every movement of value is a journal_entry holding two or more
-- ledger_lines whose signed amounts sum to zero. Negative is a debit, positive is a credit.

CREATE TABLE account
(
    id            UUID PRIMARY KEY,

    -- NULL for the internal system accounts; set for every user wallet.
    owner_user_id UUID,

    type          VARCHAR(20)  NOT NULL,

    -- VARCHAR rather than CHAR: CHAR(3) is blank-padded, so a code read back as 'INR ' would not
    -- equal the 'INR' it was written as in application code.
    currency      VARCHAR(3)   NOT NULL DEFAULT 'INR',

    -- Materialized cache of SUM(ledger_line.amount_minor) for this account. The ledger is the
    -- source of truth; this column exists so a balance read is not a full history scan. A
    -- reconciliation check proves the two agree (DESIGN.md section 5.3).
    balance_minor BIGINT       NOT NULL DEFAULT 0,

    status        VARCHAR(20)  NOT NULL DEFAULT 'ACTIVE',
    version       BIGINT       NOT NULL DEFAULT 0,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),

    CONSTRAINT account_type_chk CHECK (type IN ('USER_WALLET', 'SYSTEM_FUNDING', 'SYSTEM_FEES')),
    CONSTRAINT account_status_chk CHECK (status IN ('ACTIVE', 'FROZEN', 'CLOSED')),

    -- A user wallet always has an owner; a system account never does.
    CONSTRAINT account_owner_chk CHECK (
        (type = 'USER_WALLET' AND owner_user_id IS NOT NULL)
            OR (type <> 'USER_WALLET' AND owner_user_id IS NULL)
        ),

    -- Overdraft protection as a database backstop, independent of application logic. A user
    -- wallet can never go negative. SYSTEM_FUNDING is deliberately exempt: it runs negative by
    -- design, and its magnitude is how much value has entered the platform from outside.
    CONSTRAINT account_user_balance_chk CHECK (
        type <> 'USER_WALLET' OR balance_minor >= 0
        )
);

-- One wallet per user.
CREATE UNIQUE INDEX account_owner_uk ON account (owner_user_id) WHERE owner_user_id IS NOT NULL;

-- Exactly one account of each system type.
CREATE UNIQUE INDEX account_system_type_uk ON account (type) WHERE owner_user_id IS NULL;


CREATE TABLE journal_entry
(
    id           UUID PRIMARY KEY,
    type         VARCHAR(20)  NOT NULL,

    -- The caller's identifier for this posting — a transfer id, or a top-up idempotency key.
    -- UNIQUE is what makes posting idempotent: a retry of a posting that already committed hits
    -- this constraint instead of moving the money twice (DESIGN.md section 7.4).
    --
    -- Sized for a scoped reference such as 'topup:<uuid>:<client-key>'. Idempotency keys are
    -- namespaced per user because this column is globally unique, and two users independently
    -- choosing the key "1" must not collide.
    external_ref VARCHAR(128) NOT NULL UNIQUE,

    description  VARCHAR(255),
    posted_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),

    CONSTRAINT journal_entry_type_chk CHECK (type IN ('TOPUP', 'TRANSFER', 'REVERSAL'))
);


CREATE TABLE ledger_line
(
    id                  UUID PRIMARY KEY,
    journal_entry_id    UUID        NOT NULL REFERENCES journal_entry (id),
    account_id          UUID        NOT NULL REFERENCES account (id),

    -- Signed: negative debits the account, positive credits it.
    amount_minor        BIGINT      NOT NULL,

    -- The account's balance immediately after this line was applied, so a statement can show a
    -- running balance without recomputing history for every row.
    balance_after_minor BIGINT      NOT NULL,

    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),

    -- A zero-amount line moves nothing and is always a bug.
    CONSTRAINT ledger_line_amount_chk CHECK (amount_minor <> 0)
);

-- Statement paging: newest first, keyset-ordered by (created_at, id).
CREATE INDEX ledger_line_account_idx ON ledger_line (account_id, created_at DESC, id DESC);
CREATE INDEX ledger_line_entry_idx ON ledger_line (journal_entry_id);
