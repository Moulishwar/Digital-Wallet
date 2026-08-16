-- Users, their roles, and the refresh tokens issued to them.

CREATE TABLE app_user
(
    id            UUID PRIMARY KEY,

    -- The public identifier used to send money. Lowercase and restricted so that two handles
    -- cannot look identical to a human while differing to the database.
    handle        VARCHAR(32)  NOT NULL,

    -- Stored already lowercased by the application. A plain unique index then makes addresses
    -- case-insensitive without needing the citext extension, which keeps this schema portable to
    -- the Oracle dialect in db/oracle.
    email         VARCHAR(255) NOT NULL,

    -- BCrypt output is 60 characters; the column is sized with room to spare. The plaintext
    -- password is never stored, logged, or returned.
    password_hash VARCHAR(72)  NOT NULL,

    full_name     VARCHAR(120) NOT NULL,
    status        VARCHAR(20)  NOT NULL DEFAULT 'ACTIVE',
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),

    CONSTRAINT app_user_status_chk CHECK (status IN ('ACTIVE', 'SUSPENDED')),
    CONSTRAINT app_user_handle_format_chk CHECK (handle ~ '^[a-z0-9_]{3,32}$'),
    CONSTRAINT app_user_email_lowercase_chk CHECK (email = lower(email))
);

CREATE UNIQUE INDEX app_user_handle_uk ON app_user (handle);
CREATE UNIQUE INDEX app_user_email_uk ON app_user (email);


CREATE TABLE user_role
(
    user_id UUID        NOT NULL REFERENCES app_user (id) ON DELETE CASCADE,
    role    VARCHAR(32) NOT NULL,

    PRIMARY KEY (user_id, role),
    CONSTRAINT user_role_chk CHECK (role IN ('ROLE_USER', 'ROLE_ADMIN'))
);


CREATE TABLE refresh_token
(
    id          UUID PRIMARY KEY,
    user_id     UUID        NOT NULL REFERENCES app_user (id) ON DELETE CASCADE,

    -- SHA-256 of the token, hex encoded. The raw token exists only in the response that returned
    -- it and in the client's hands: a leaked database gives an attacker nothing usable.
    -- VARCHAR, not CHAR: a blank-padded fixed-width type would not compare equal to the
    -- unpadded hex string the application computes.
    token_hash  VARCHAR(64) NOT NULL,

    issued_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at  TIMESTAMPTZ NOT NULL,

    -- Set when the token is rotated away or explicitly revoked. NULL means live.
    revoked_at  TIMESTAMPTZ,

    -- The token issued in this one's place. Forms a chain, which is what makes theft detectable:
    -- if an already-rotated token is presented again, either the client or an attacker is
    -- replaying it, and the whole chain is burned.
    replaced_by UUID REFERENCES refresh_token (id)
);

CREATE UNIQUE INDEX refresh_token_hash_uk ON refresh_token (token_hash);
CREATE INDEX refresh_token_user_idx ON refresh_token (user_id, revoked_at);
