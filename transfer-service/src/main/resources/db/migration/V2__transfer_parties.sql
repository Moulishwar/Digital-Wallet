-- How each party to a transfer was named when it was made.
--
-- The ids above stay the authority: ownership, the posting, and reconciliation all key on them.
-- These columns are labels, captured once so that the sender's history can say who they paid
-- without calling auth-service per row, and so that a retried posting from the reconciliation
-- sweep carries the same statement labels as the original attempt would have.
--
-- Nullable because transfers made before this migration have none, and a label missing from an
-- old row is a cosmetic gap, not a reason to refuse to read it.

ALTER TABLE transfer
    ADD COLUMN sender_handle    VARCHAR(32),
    ADD COLUMN sender_name      VARCHAR(120),
    ADD COLUMN recipient_handle VARCHAR(32),
    ADD COLUMN recipient_name   VARCHAR(120);
