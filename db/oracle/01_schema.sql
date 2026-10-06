-- =============================================================================================
--  Digital Wallet — ledger schema, Oracle dialect
-- =============================================================================================
--
--  The same model as wallet-service's Flyway migrations, expressed in Oracle. This is not a port
--  of the running system: the services run on PostgreSQL. It exists so the data model can be read
--  and exercised in Oracle, alongside the analytical queries in 03_reports.sql.
--
--  Run in order:  01_schema.sql -> 02_seed.sql -> 03_reports.sql
--
--  What changes between the dialects, and why:
--
--    UUID            -> RAW(16). Oracle has no UUID type. RAW(16) stores the 128 bits exactly and
--                       compares faster than the 36-character text form, and SYS_GUID() produces
--                       one natively. VARCHAR2(36) would be more readable but 2.25x the storage
--                       on every foreign key.
--    BIGINT          -> NUMBER(19). Same range as a 64-bit signed integer. Note this is still an
--                       exact type: money is never held in anything that rounds.
--    TIMESTAMPTZ     -> TIMESTAMP WITH TIME ZONE.
--    VARCHAR         -> VARCHAR2. Never CHAR — it blank-pads, so 'INR ' would not equal 'INR'.
--    partial index   -> function-based unique index. Oracle indexes ignore all-NULL keys, so a
--                       CASE expression reproduces "unique only where the value is not null".
--    BEFORE trigger  -> compound/row trigger raising an application error.
-- =============================================================================================

-- Drop in dependency order so the script can be re-run against a dirty schema.
BEGIN
    FOR t IN (SELECT table_name FROM user_tables
              WHERE table_name IN ('LEDGER_LINE', 'JOURNAL_ENTRY', 'ACCOUNT', 'DAILY_ACCOUNT_ROLLUP'))
        LOOP
            EXECUTE IMMEDIATE 'DROP TABLE ' || t.table_name || ' CASCADE CONSTRAINTS PURGE';
        END LOOP;
END;
/


-- ---------------------------------------------------------------------------------------------
--  account
-- ---------------------------------------------------------------------------------------------
CREATE TABLE account
(
    id            RAW(16) DEFAULT SYS_GUID() NOT NULL,

    -- NULL for the internal system accounts; set for every user wallet.
    owner_user_id RAW(16),

    type          VARCHAR2(20)               NOT NULL,

    currency      VARCHAR2(3) DEFAULT 'INR'  NOT NULL,

    -- Materialized cache of SUM(ledger_line.amount_minor) for this account. The ledger is the
    -- source of truth; this column exists so a balance read is not a full history scan. Query 4
    -- in 03_reports.sql proves the two agree.
    balance_minor NUMBER(19) DEFAULT 0       NOT NULL,

    status        VARCHAR2(20) DEFAULT 'ACTIVE' NOT NULL,
    version       NUMBER(19) DEFAULT 0       NOT NULL,
    created_at    TIMESTAMP WITH TIME ZONE DEFAULT SYSTIMESTAMP NOT NULL,

    CONSTRAINT account_pk PRIMARY KEY (id),
    CONSTRAINT account_type_chk CHECK (type IN ('USER_WALLET', 'SYSTEM_FUNDING', 'SYSTEM_FEES')),
    CONSTRAINT account_status_chk CHECK (status IN ('ACTIVE', 'FROZEN', 'CLOSED')),

    -- A user wallet always has an owner; a system account never does.
    CONSTRAINT account_owner_chk CHECK (
        (type = 'USER_WALLET' AND owner_user_id IS NOT NULL)
            OR (type <> 'USER_WALLET' AND owner_user_id IS NULL)
        ),

    -- Overdraft protection as a database backstop, independent of application logic. A user wallet
    -- can never go negative. SYSTEM_FUNDING is deliberately exempt: it runs negative by design, and
    -- its magnitude is how much value has entered the platform from outside.
    CONSTRAINT account_user_balance_chk CHECK (
        type <> 'USER_WALLET' OR balance_minor >= 0
        )
);

-- One wallet per user. PostgreSQL expresses this as a partial index; Oracle has no WHERE clause on
-- an index, but it does skip entries whose key is entirely NULL — so a CASE that yields NULL for
-- system accounts gives exactly the same guarantee.
CREATE UNIQUE INDEX account_owner_uk ON account (
    CASE WHEN owner_user_id IS NOT NULL THEN owner_user_id END
    );

-- Exactly one account of each system type.
CREATE UNIQUE INDEX account_system_type_uk ON account (
    CASE WHEN owner_user_id IS NULL THEN type END
    );


-- ---------------------------------------------------------------------------------------------
--  journal_entry — one movement of value
-- ---------------------------------------------------------------------------------------------
CREATE TABLE journal_entry
(
    id           RAW(16) DEFAULT SYS_GUID() NOT NULL,
    type         VARCHAR2(20)               NOT NULL,

    -- The caller's identifier for this posting — a transfer id, or a scoped top-up key. UNIQUE is
    -- what makes posting idempotent: a retry of a posting that already committed collides here
    -- instead of moving the money a second time.
    external_ref VARCHAR2(128)              NOT NULL,

    description  VARCHAR2(255),
    posted_at    TIMESTAMP WITH TIME ZONE DEFAULT SYSTIMESTAMP NOT NULL,

    -- The sender's note, shown on both parties' statements (Postgres migration V4).
    memo         VARCHAR2(140),

    CONSTRAINT journal_entry_pk PRIMARY KEY (id),
    CONSTRAINT journal_entry_ref_uk UNIQUE (external_ref),
    CONSTRAINT journal_entry_type_chk CHECK (type IN ('TOPUP', 'TRANSFER', 'REVERSAL'))
);


-- ---------------------------------------------------------------------------------------------
--  ledger_line — the two-or-more sides of an entry, which must sum to zero
-- ---------------------------------------------------------------------------------------------
CREATE TABLE ledger_line
(
    id                  RAW(16) DEFAULT SYS_GUID() NOT NULL,
    journal_entry_id    RAW(16)                    NOT NULL,
    account_id          RAW(16)                    NOT NULL,

    -- Signed: negative debits the account, positive credits it.
    amount_minor        NUMBER(19)                 NOT NULL,

    -- The account's balance immediately after this line was applied, so a statement can show a
    -- running balance without recomputing history for every row. Query 2 in 03_reports.sql
    -- reconstructs it with a window function and checks it against this column.
    balance_after_minor NUMBER(19)                 NOT NULL,

    created_at          TIMESTAMP WITH TIME ZONE DEFAULT SYSTIMESTAMP NOT NULL,

    -- Who the money on this line came from or went to, captured at posting time (Postgres
    -- migration V4). NULL for top-ups, whose other side is the funding account.
    counterparty_handle VARCHAR2(32),
    counterparty_name   VARCHAR2(120),

    CONSTRAINT ledger_line_pk PRIMARY KEY (id),
    CONSTRAINT ledger_line_entry_fk FOREIGN KEY (journal_entry_id) REFERENCES journal_entry (id),
    CONSTRAINT ledger_line_account_fk FOREIGN KEY (account_id) REFERENCES account (id),

    -- A zero-amount line moves nothing and is always a bug.
    CONSTRAINT ledger_line_amount_chk CHECK (amount_minor <> 0)
);

-- Statement paging: newest first, keyset-ordered by (created_at, id).
CREATE INDEX ledger_line_account_idx ON ledger_line (account_id, created_at DESC, id DESC);
CREATE INDEX ledger_line_entry_idx ON ledger_line (journal_entry_id);


-- ---------------------------------------------------------------------------------------------
--  Append-only enforcement
-- ---------------------------------------------------------------------------------------------
--  There is no correct reason to update or delete a ledger line. A mistaken posting is corrected
--  by writing a REVERSAL entry, never by editing history.
--
--  Enforced at the database, not only in the application, so it also holds for a SQL*Plus session,
--  a migration script, and anyone holding a connection string.
-- ---------------------------------------------------------------------------------------------
CREATE OR REPLACE TRIGGER ledger_line_append_only
    BEFORE UPDATE OR DELETE
    ON ledger_line
BEGIN
    RAISE_APPLICATION_ERROR(
            -20001,
            'ledger_line is append-only. Post a REVERSAL journal entry instead of modifying ledger history.');
END;
/


-- ---------------------------------------------------------------------------------------------
--  daily_account_rollup — pre-aggregated daily movement, maintained by the MERGE in 03_reports.sql
-- ---------------------------------------------------------------------------------------------
CREATE TABLE daily_account_rollup
(
    account_id     RAW(16)    NOT NULL,
    activity_date  DATE       NOT NULL,
    line_count     NUMBER(10) DEFAULT 0 NOT NULL,
    credited_minor NUMBER(19) DEFAULT 0 NOT NULL,
    debited_minor  NUMBER(19) DEFAULT 0 NOT NULL,
    net_minor      NUMBER(19) DEFAULT 0 NOT NULL,
    refreshed_at   TIMESTAMP WITH TIME ZONE DEFAULT SYSTIMESTAMP NOT NULL,

    CONSTRAINT daily_account_rollup_pk PRIMARY KEY (account_id, activity_date),
    CONSTRAINT daily_account_rollup_account_fk FOREIGN KEY (account_id) REFERENCES account (id)
);


-- ---------------------------------------------------------------------------------------------
--  System accounts
-- ---------------------------------------------------------------------------------------------
--  Value cannot appear from nowhere in a double-entry system: a top-up credits a user wallet, so
--  something must be debited. SYSTEM_FUNDING is that counterparty — it stands for the bank rail the
--  money arrives from, and its balance is expected to be large and negative.
--
--  Ids are fixed rather than generated so application code can hold them as constants.
-- ---------------------------------------------------------------------------------------------
INSERT INTO account (id, owner_user_id, type, currency, balance_minor, status)
VALUES (HEXTORAW('00000000000000000000000000000001'), NULL, 'SYSTEM_FUNDING', 'INR', 0, 'ACTIVE');

INSERT INTO account (id, owner_user_id, type, currency, balance_minor, status)
VALUES (HEXTORAW('00000000000000000000000000000002'), NULL, 'SYSTEM_FEES', 'INR', 0, 'ACTIVE');

COMMIT;
