-- Ledger lines are append-only. There is no correct reason to update or delete one.
--
-- This is already enforced in JPA (no setters, @Immutable) and in the repository layer (no
-- mutating method is exposed). This migration adds the third and final layer, at the database,
-- where it also holds for psql sessions, migration scripts, and anyone with a connection string.
--
-- A mistaken posting is corrected by writing a REVERSAL entry, never by editing history.

CREATE OR REPLACE FUNCTION ledger_line_reject_mutation() RETURNS TRIGGER AS
$$
BEGIN
    RAISE EXCEPTION 'ledger_line is append-only: % is not permitted (row %)', TG_OP, OLD.id
        USING ERRCODE = 'restrict_violation',
            HINT = 'Post a REVERSAL journal entry instead of modifying ledger history.';
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER ledger_line_no_update
    BEFORE UPDATE
    ON ledger_line
    FOR EACH ROW
EXECUTE FUNCTION ledger_line_reject_mutation();

CREATE TRIGGER ledger_line_no_delete
    BEFORE DELETE
    ON ledger_line
    FOR EACH ROW
EXECUTE FUNCTION ledger_line_reject_mutation();
