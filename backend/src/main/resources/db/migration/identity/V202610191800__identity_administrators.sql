-- Administrators (FR-2, AD-19, AD-22, AD-34): their password and TOTP second factor, and the sign-in
-- failures that lock an email for 15 minutes. Personal data: exported (email and name only) and
-- deleted on erasure (AD-24); the password is an Argon2 hash and the TOTP secret is AES-GCM sealed.

-- One per Administrator account. last_totp_step is the last 30 s step accepted, so each is used once.
CREATE TABLE identity.administrator_credential
(
    account_id       UUID PRIMARY KEY REFERENCES identity.account (id),
    email            TEXT        NOT NULL UNIQUE CHECK (email = lower(email) AND char_length(email) BETWEEN 3 AND 254),
    display_name     TEXT        NOT NULL CHECK (char_length(display_name) BETWEEN 1 AND 100),
    password_hash    TEXT        NOT NULL CHECK (password_hash LIKE '$argon2id$%'),
    totp_key_version INTEGER     NOT NULL CHECK (totp_key_version > 0),
    totp_nonce       BYTEA       NOT NULL,
    totp_ciphertext  BYTEA       NOT NULL,
    last_totp_step   BIGINT,
    created_at       TIMESTAMPTZ NOT NULL
);

GRANT SELECT, INSERT, UPDATE, DELETE ON identity.administrator_credential TO ${runtime_role};

-- Failed Administrator sign-ins per email (its SHA-256, so the table holds no address), and the
-- emails locked after 5 failures within 15 minutes. Both are purged once they no longer count.
CREATE TABLE identity.admin_sign_in_failure
(
    id           UUID PRIMARY KEY,
    email_hash   TEXT        NOT NULL CHECK (email_hash ~ '^[0-9a-f]{64}$'),
    failed_at    TIMESTAMPTZ NOT NULL
);

CREATE INDEX admin_sign_in_failure_email ON identity.admin_sign_in_failure (email_hash, failed_at);

GRANT SELECT, INSERT, DELETE ON identity.admin_sign_in_failure TO ${runtime_role};

CREATE TABLE identity.admin_sign_in_lock
(
    email_hash   TEXT PRIMARY KEY CHECK (email_hash ~ '^[0-9a-f]{64}$'),
    locked_until TIMESTAMPTZ NOT NULL
);

GRANT SELECT, INSERT, UPDATE, DELETE ON identity.admin_sign_in_lock TO ${runtime_role};
