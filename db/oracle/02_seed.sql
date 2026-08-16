-- =============================================================================================
--  Digital Wallet — seed data, Oracle dialect
-- =============================================================================================
--
--  20 users, one wallet each, one top-up each, and 300 transfers spread over roughly seven months.
--  Enough volume and enough spread that the window functions in 03_reports.sql produce output
--  worth reading rather than a handful of rows that prove nothing.
--
--  Everything here is DETERMINISTIC — fixed ids, fixed dates, fixed amounts, no DBMS_RANDOM. That
--  is the whole point: the expected results committed alongside 03_reports.sql are only meaningful
--  if the same script produces the same data every time, on any machine.
--
--  Ids are generated from an integer so they are readable and reproducible: wallet n is
--  0..01000+n, its owner is 0..02000+n. Digits are valid hex, so LPAD to 32 characters gives a
--  well-formed RAW(16).
--
--  Note that the seed respects the ledger's rules rather than working around them. Every movement
--  writes a balanced pair of lines, balance_after_minor is maintained as it goes, and the cached
--  account balance is written from the same arithmetic — so query 4 (reconciliation) and query 5
--  (zero-sum) are genuine checks here, not tautologies.
-- =============================================================================================

DELETE FROM daily_account_rollup;
DELETE FROM journal_entry WHERE 1 = 0;   -- placeholder: lines must go first
COMMIT;

-- ledger_line is append-only, and the trigger means even this script cannot simply delete from it.
-- That is the protection working as intended; disabling it for the length of a reseed is a
-- deliberate, visible exception rather than a hole in the design.
ALTER TRIGGER ledger_line_append_only DISABLE;
DELETE FROM ledger_line;
ALTER TRIGGER ledger_line_append_only ENABLE;

DELETE FROM journal_entry;
DELETE FROM account WHERE owner_user_id IS NOT NULL;
UPDATE account SET balance_minor = 0 WHERE owner_user_id IS NULL;
COMMIT;


DECLARE
    c_users          CONSTANT PLS_INTEGER := 20;
    c_transfers      CONSTANT PLS_INTEGER := 300;

    -- 500.00 into every wallet, so no transfer below can overdraw one. The CHECK constraint would
    -- reject it if one did, which is exactly why the seed has to be arithmetically honest.
    c_topup_minor    CONSTANT NUMBER(19) := 50000;

    c_funding_id     CONSTANT RAW(16) := HEXTORAW('00000000000000000000000000000001');
    c_start_date     CONSTANT TIMESTAMP WITH TIME ZONE :=
        TO_TIMESTAMP_TZ('2026-01-05 09:00:00 +00:00', 'YYYY-MM-DD HH24:MI:SS TZH:TZM');

    TYPE id_table IS TABLE OF RAW(16) INDEX BY PLS_INTEGER;
    TYPE amount_table IS TABLE OF NUMBER(19) INDEX BY PLS_INTEGER;

    wallet_id        id_table;
    balance          amount_table;
    funding_balance  NUMBER(19) := 0;

    entry_seq        PLS_INTEGER := 0;
    line_seq         PLS_INTEGER := 0;

    FUNCTION as_id(prefix IN PLS_INTEGER, n IN PLS_INTEGER) RETURN RAW IS
    BEGIN
        RETURN HEXTORAW(LPAD(TO_CHAR(prefix + n), 32, '0'));
    END;

    PROCEDURE write_line(p_entry_id IN RAW, p_account_id IN RAW, p_amount IN NUMBER,
                         p_balance_after IN NUMBER, p_at IN TIMESTAMP WITH TIME ZONE) IS
    BEGIN
        line_seq := line_seq + 1;
        INSERT INTO ledger_line (id, journal_entry_id, account_id, amount_minor,
                                 balance_after_minor, created_at)
        VALUES (as_id(40000000, line_seq), p_entry_id, p_account_id, p_amount, p_balance_after, p_at);
    END;

BEGIN
    -- ------------------------------------------------------------------ wallets
    FOR i IN 1 .. c_users
        LOOP
            wallet_id(i) := as_id(1000, i);
            balance(i) := 0;

            INSERT INTO account (id, owner_user_id, type, currency, balance_minor, status, created_at)
            VALUES (wallet_id(i), as_id(2000, i), 'USER_WALLET', 'INR', 0, 'ACTIVE',
                    c_start_date - NUMTODSINTERVAL(1, 'DAY'));
        END LOOP;

    -- ------------------------------------------------------------------ top-ups
    --
    -- Money entering the platform. The funding account is debited so the entry balances: value
    -- comes from outside, it does not appear from nowhere.
    FOR i IN 1 .. c_users
        LOOP
            entry_seq := entry_seq + 1;

            DECLARE
                v_entry_id RAW(16) := as_id(30000000, entry_seq);
                v_at       TIMESTAMP WITH TIME ZONE := c_start_date + NUMTODSINTERVAL(i, 'HOUR');
            BEGIN
                INSERT INTO journal_entry (id, type, external_ref, description, posted_at)
                VALUES (v_entry_id, 'TOPUP', 'seed-topup-' || TO_CHAR(i), 'Wallet top-up', v_at);

                funding_balance := funding_balance - c_topup_minor;
                balance(i) := balance(i) + c_topup_minor;

                write_line(v_entry_id, c_funding_id, -c_topup_minor, funding_balance, v_at);
                write_line(v_entry_id, wallet_id(i), c_topup_minor, balance(i), v_at);
            END;
        END LOOP;

    -- ---------------------------------------------------------------- transfers
    --
    -- Sender, recipient and amount are all derived from the loop counter using co-prime strides,
    -- so the traffic is uneven — some users send far more than others — without being random.
    -- Even traffic would make the RANK() queries below meaningless.
    FOR n IN 1 .. c_transfers
        LOOP
            DECLARE
                v_sender    PLS_INTEGER := MOD(n * 7, c_users) + 1;
                v_recipient PLS_INTEGER := MOD(n * 13 + 3, c_users) + 1;
                v_amount    NUMBER(19) := 100 + MOD(n * 37, 90) * 10;
                v_entry_id  RAW(16);
                v_at        TIMESTAMP WITH TIME ZONE;
            BEGIN
                -- A transfer needs two different parties.
                IF v_sender = v_recipient THEN
                    v_recipient := MOD(v_recipient + 5, c_users) + 1;
                END IF;

                IF v_sender <> v_recipient AND balance(v_sender) >= v_amount THEN
                    entry_seq := entry_seq + 1;
                    v_entry_id := as_id(30000000, entry_seq);
                    -- Spread across about seven months, with several per day, so the monthly
                    -- aggregation has something to aggregate.
                    v_at := c_start_date + NUMTODSINTERVAL(n * 17, 'HOUR');

                    INSERT INTO journal_entry (id, type, external_ref, description, posted_at)
                    VALUES (v_entry_id, 'TRANSFER', 'seed-transfer-' || TO_CHAR(n),
                            'Transfer ' || TO_CHAR(n), v_at);

                    balance(v_sender) := balance(v_sender) - v_amount;
                    balance(v_recipient) := balance(v_recipient) + v_amount;

                    write_line(v_entry_id, wallet_id(v_sender), -v_amount, balance(v_sender), v_at);
                    write_line(v_entry_id, wallet_id(v_recipient), v_amount, balance(v_recipient), v_at);
                END IF;
            END;
        END LOOP;

    -- ------------------------------------------------- cached balances, written last
    --
    -- From the same running arithmetic the lines were written with. If that arithmetic is wrong,
    -- query 4 will say so rather than agreeing with itself.
    FOR i IN 1 .. c_users
        LOOP
            UPDATE account SET balance_minor = balance(i) WHERE id = wallet_id(i);
        END LOOP;

    UPDATE account SET balance_minor = funding_balance WHERE id = c_funding_id;

    COMMIT;

    DBMS_OUTPUT.PUT_LINE('Seeded ' || c_users || ' wallets, '
        || entry_seq || ' journal entries, ' || line_seq || ' ledger lines.');
END;
/

-- A quick sanity check, so a broken seed is obvious immediately rather than showing up as a
-- confusing report three queries later.
SELECT (SELECT COUNT(*) FROM account WHERE owner_user_id IS NOT NULL) AS wallets,
       (SELECT COUNT(*) FROM journal_entry)                           AS entries,
       (SELECT COUNT(*) FROM ledger_line)                             AS lines,
       (SELECT SUM(amount_minor) FROM ledger_line)                    AS must_be_zero
FROM dual;

-- Expected:
--   WALLETS  ENTRIES  LINES  MUST_BE_ZERO
--   -------  -------  -----  ------------
--        20      320    640             0
