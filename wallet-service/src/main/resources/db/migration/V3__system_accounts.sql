-- The internal accounts that represent the world outside the platform.
--
-- Value cannot appear from nowhere in a double-entry system: a top-up credits a user wallet, so
-- something must be debited. SYSTEM_FUNDING is that counterparty — it stands for the bank rail
-- money arrives from. Its balance is expected to be large and negative, and its magnitude is
-- exactly how much value has entered the platform.
--
-- SYSTEM_FEES is unused in v1 and reserved for fee collection.
--
-- Ids are fixed rather than random so application code can reference them as constants and so
-- this migration is idempotent across environments.

INSERT INTO account (id, owner_user_id, type, currency, balance_minor, status)
VALUES ('00000000-0000-0000-0000-000000000001', NULL, 'SYSTEM_FUNDING', 'INR', 0, 'ACTIVE'),
       ('00000000-0000-0000-0000-000000000002', NULL, 'SYSTEM_FEES', 'INR', 0, 'ACTIVE');
