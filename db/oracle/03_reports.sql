-- =============================================================================================
--  Digital Wallet — analytical queries, Oracle dialect
-- =============================================================================================
--
--  Run 01_schema.sql and 02_seed.sql first. Every expected result below is against that seed,
--  which is deterministic, so these are checkable rather than decorative.
--
--  A note on what is absent: there are no names here, only owner_user_id. Names live in
--  auth-service's database, and nothing in this system can join across that boundary — no shared
--  tables, no cross-database queries. That constraint is the architecture
--  showing through into the reporting layer, not an oversight. A real reporting stack would
--  resolve those ids through the API or a warehouse that both services feed.
-- =============================================================================================

SET LINESIZE 200
SET PAGESIZE 100


-- ---------------------------------------------------------------------------------------------
--  1. Monthly transaction volume per wallet, with a running cumulative total
-- ---------------------------------------------------------------------------------------------
--  The aggregate and the window do different jobs and both are needed: GROUP BY collapses each
--  month to a row, and SUM() OVER (PARTITION BY ... ORDER BY ...) then accumulates across those
--  rows without collapsing them further. Doing this with a self-join would be one join per month
--  and quadratic; the window function is one pass over an already-sorted partition.
-- ---------------------------------------------------------------------------------------------
SELECT a.owner_user_id,
       TO_CHAR(l.created_at, 'YYYY-MM')                            AS month,
       COUNT(*)                                                    AS line_count,
       SUM(CASE WHEN l.amount_minor > 0 THEN l.amount_minor ELSE 0 END)  AS credited_minor,
       SUM(CASE WHEN l.amount_minor < 0 THEN -l.amount_minor ELSE 0 END) AS debited_minor,
       SUM(SUM(l.amount_minor)) OVER (
           PARTITION BY a.owner_user_id
           ORDER BY TO_CHAR(l.created_at, 'YYYY-MM')
           ROWS BETWEEN UNBOUNDED PRECEDING AND CURRENT ROW
           )                                                       AS running_net_minor
FROM ledger_line l
         JOIN account a ON a.id = l.account_id
WHERE a.owner_user_id IS NOT NULL
GROUP BY a.owner_user_id, TO_CHAR(l.created_at, 'YYYY-MM')
ORDER BY a.owner_user_id, month;

-- Expected: 8 months of activity (2026-01 through 2026-08) across 20 wallets.
-- The final running_net_minor for each wallet equals its current balance, between 48350 and 51650.
-- Transfer volume by month, for reference:
--   2026-01: 37 transfers,  20810 minor      2026-05: 43 transfers,  23550 minor
--   2026-02: 40 transfers,  21600 minor      2026-06: 43 transfers,  23680 minor
--   2026-03: 43 transfers,  23290 minor      2026-07: 44 transfers,  23420 minor
--   2026-04: 43 transfers,  23420 minor      2026-08:  7 transfers,   4030 minor


-- ---------------------------------------------------------------------------------------------
--  2. Running balance reconstruction, checked against the stored balance_after_minor
-- ---------------------------------------------------------------------------------------------
--  balance_after_minor is written at posting time so a statement can show a running balance
--  without recomputing history. This rebuilds it independently from the amounts alone and compares.
--
--  The ORDER BY inside the window must match the order the lines were actually applied in, which
--  is why (created_at, id) is used rather than created_at alone — two lines of the same entry share
--  a timestamp, and an ambiguous ordering would produce a "mismatch" that is really just a tie
--  broken differently.
-- ---------------------------------------------------------------------------------------------
WITH reconstructed AS (SELECT l.id,
                              a.owner_user_id,
                              l.created_at,
                              l.amount_minor,
                              l.balance_after_minor                          AS stored_balance,
                              SUM(l.amount_minor) OVER (
                                  PARTITION BY l.account_id
                                  ORDER BY l.created_at, l.id
                                  ROWS BETWEEN UNBOUNDED PRECEDING AND CURRENT ROW
                                  )                                          AS derived_balance
                       FROM ledger_line l
                                JOIN account a ON a.id = l.account_id)
SELECT id, owner_user_id, created_at, amount_minor, stored_balance, derived_balance,
       stored_balance - derived_balance AS drift_minor
FROM reconstructed
WHERE stored_balance <> derived_balance
ORDER BY created_at;

-- Expected: no rows. Every stored running balance is reproducible from the amounts alone.


-- ---------------------------------------------------------------------------------------------
--  3. Top senders and recipients, ranked
-- ---------------------------------------------------------------------------------------------
--  RANK and DENSE_RANK side by side because the seed contains a genuine tie, which is the only
--  situation in which they differ and therefore the only situation worth showing. Two wallets send
--  8850 each: both are rank 3, and the next wallet is rank 5 under RANK but rank 4 under
--  DENSE_RANK. Picking one blindly is how "top 10" lists quietly return 11 rows or skip one.
-- ---------------------------------------------------------------------------------------------
WITH movement AS (SELECT a.owner_user_id,
                         SUM(CASE WHEN l.amount_minor < 0 THEN -l.amount_minor ELSE 0 END) AS sent_minor,
                         SUM(CASE WHEN l.amount_minor > 0 THEN l.amount_minor ELSE 0 END)  AS received_minor,
                         COUNT(CASE WHEN l.amount_minor < 0 THEN 1 END)                    AS sends
                  FROM ledger_line l
                           JOIN account a ON a.id = l.account_id
                           JOIN journal_entry j ON j.id = l.journal_entry_id
                  WHERE a.owner_user_id IS NOT NULL
                    -- Top-ups are money entering the platform, not one user paying another.
                    -- Counting them here would rank whoever deposited most, not whoever is active.
                    AND j.type = 'TRANSFER'
                  GROUP BY a.owner_user_id)
SELECT owner_user_id,
       sent_minor,
       sends,
       received_minor,
       RANK() OVER (ORDER BY sent_minor DESC)           AS send_rank,
       DENSE_RANK() OVER (ORDER BY sent_minor DESC)     AS send_dense_rank,
       RANK() OVER (ORDER BY received_minor DESC)       AS receive_rank
FROM movement
ORDER BY sent_minor DESC
FETCH FIRST 10 ROWS ONLY;

-- Expected, top five by amount sent (owner_user_id shown by its trailing digits):
--   ...2019   9300   15 sends   send_rank 1   dense 1
--   ...2010   9150   15 sends   send_rank 2   dense 2
--   ...2018   8850   15 sends   send_rank 3   dense 3   <-- tie
--   ...2020   8850   15 sends   send_rank 3   dense 3   <-- tie
--   ...2009   8700   15 sends   send_rank 5   dense 4   <-- RANK skips 4, DENSE_RANK does not
--
-- Top three by amount received: ...2006 (9300), ...2015 (9150), then ...2005 and ...2007 tied at 8850.


-- ---------------------------------------------------------------------------------------------
--  4. Reconciliation — the cached balance against the ledger it summarises
-- ---------------------------------------------------------------------------------------------
--  account.balance_minor is a materialized cache of the sum of an account's ledger lines, kept up
--  to date inside the same transaction as the posting. The ledger stays the source of truth. This
--  is the query that proves the cache has not drifted from it — the same assertion the running
--  system exposes at GET /api/admin/reconciliation.
--
--  A LEFT JOIN, not an inner one: an account with no lines at all still has a balance to check,
--  and an inner join would silently skip exactly the accounts most likely to be wrong.
-- ---------------------------------------------------------------------------------------------
SELECT a.id,
       a.owner_user_id,
       a.type,
       a.balance_minor                        AS cached_balance,
       NVL(SUM(l.amount_minor), 0)            AS derived_balance,
       a.balance_minor - NVL(SUM(l.amount_minor), 0) AS drift_minor
FROM account a
         LEFT JOIN ledger_line l ON l.account_id = a.id
GROUP BY a.id, a.owner_user_id, a.type, a.balance_minor
HAVING a.balance_minor <> NVL(SUM(l.amount_minor), 0)
ORDER BY a.type, a.owner_user_id;

-- Expected: no rows. Every cached balance equals the sum of its own lines.


-- ---------------------------------------------------------------------------------------------
--  5. The zero-sum audit
-- ---------------------------------------------------------------------------------------------
--  The invariant the whole design rests on: every journal entry's lines sum to exactly zero. A
--  non-zero entry means money was created or destroyed by that posting.
--
--  PostgreSQL cannot express this as a simple CHECK — the constraint spans rows — so the running
--  system enforces it in the service layer and verifies it with a test. This is the standing audit
--  that would catch it if something ever wrote around that layer.
-- ---------------------------------------------------------------------------------------------
SELECT j.id            AS journal_entry_id,
       j.type,
       j.external_ref,
       COUNT(l.id)     AS line_count,
       SUM(l.amount_minor) AS imbalance_minor
FROM journal_entry j
         JOIN ledger_line l ON l.journal_entry_id = j.id
GROUP BY j.id, j.type, j.external_ref
HAVING SUM(l.amount_minor) <> 0
    -- A single-sided entry is unbalanced even when its one line happens to sum to zero, which a
    -- zero-amount line could. The amount CHECK forbids that, and this is the belt to its braces.
    OR COUNT(l.id) < 2
ORDER BY j.posted_at;

-- Expected: no rows, across all 320 entries.

-- And the same invariant over the whole table at once:
SELECT COUNT(*) AS total_lines, SUM(amount_minor) AS must_be_zero FROM ledger_line;
-- Expected:  TOTAL_LINES 640,  MUST_BE_ZERO 0


-- ---------------------------------------------------------------------------------------------
--  6. Daily rollup, maintained with MERGE
-- ---------------------------------------------------------------------------------------------
--  MERGE rather than "delete the day, then insert it": one statement, one pass, and no window in
--  which the day's row does not exist. Re-running it is safe and updates in place, which is what
--  makes it usable as a nightly job that might be run twice.
-- ---------------------------------------------------------------------------------------------
MERGE INTO daily_account_rollup tgt
USING (SELECT l.account_id,
              TRUNC(CAST(l.created_at AS DATE))                                 AS activity_date,
              COUNT(*)                                                          AS line_count,
              SUM(CASE WHEN l.amount_minor > 0 THEN l.amount_minor ELSE 0 END)  AS credited_minor,
              SUM(CASE WHEN l.amount_minor < 0 THEN -l.amount_minor ELSE 0 END) AS debited_minor,
              SUM(l.amount_minor)                                               AS net_minor
       FROM ledger_line l
       GROUP BY l.account_id, TRUNC(CAST(l.created_at AS DATE))) src
ON (tgt.account_id = src.account_id AND tgt.activity_date = src.activity_date)
WHEN MATCHED THEN
    UPDATE
    SET tgt.line_count     = src.line_count,
        tgt.credited_minor = src.credited_minor,
        tgt.debited_minor  = src.debited_minor,
        tgt.net_minor      = src.net_minor,
        tgt.refreshed_at   = SYSTIMESTAMP
WHEN NOT MATCHED THEN
    INSERT (account_id, activity_date, line_count, credited_minor, debited_minor, net_minor)
    VALUES (src.account_id, src.activity_date, src.line_count, src.credited_minor,
            src.debited_minor, src.net_minor);

COMMIT;

-- The rollup must agree with the ledger it was built from. If it does not, the rollup is wrong —
-- the ledger is never wrong by definition, because it is the record.
SELECT (SELECT SUM(net_minor) FROM daily_account_rollup)  AS rollup_total,
       (SELECT SUM(amount_minor) FROM ledger_line)        AS ledger_total,
       (SELECT SUM(line_count) FROM daily_account_rollup) AS rollup_lines,
       (SELECT COUNT(*) FROM ledger_line)                 AS ledger_lines
FROM dual;

-- Expected:  ROLLUP_TOTAL 0,  LEDGER_TOTAL 0,  ROLLUP_LINES 640,  LEDGER_LINES 640
-- Re-running the MERGE changes those numbers not at all, which is the point of it being a MERGE.
