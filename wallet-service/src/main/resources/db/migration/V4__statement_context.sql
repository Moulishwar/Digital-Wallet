-- What a person needs to read their own statement: who the money came from or went to, and the
-- note the sender attached.
--
-- Both are captured at posting time and never looked up again, the same way a bank statement
-- prints the payee as they were when the payment went out. Resolving them at read time instead
-- would mean wallet-service calling auth-service for every statement page, and a renamed or
-- deleted user rewriting history that is supposed to be fixed.
--
-- All three columns are nullable. Top-ups have no counterparty and transfers need not carry a
-- note, and rows posted before this migration have neither.

-- Shared by every line of the entry: the note is about the movement, not about one side of it.
ALTER TABLE journal_entry
    ADD COLUMN memo VARCHAR(140);

-- Per line rather than per entry, because the answer differs by side: on the sender's line the
-- counterparty is the recipient, and on the recipient's line it is the sender. A line therefore
-- describes itself without a second query for "the other line".
--
-- Adding nullable columns is a catalogue change only. It rewrites no rows, so the append-only
-- triggers from V2 are neither fired nor weakened.
ALTER TABLE ledger_line
    ADD COLUMN counterparty_handle VARCHAR(32),
    ADD COLUMN counterparty_name   VARCHAR(120);
