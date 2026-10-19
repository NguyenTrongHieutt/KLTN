-- Administrators created by Administrators (FR-2, SG-10): the new account's enrolment, through a
-- single-use link emailed to it and valid for 24 hours. Personal data: exported (email and name)
-- and deleted on erasure (AD-24). The token is kept only as its SHA-256; the TOTP secret, sealed
-- with the token keyring (AD-22), exists from the first time the enrolment page shows it.
CREATE TABLE identity.administrator_enrolment
(
    id               UUID PRIMARY KEY,
    account_id       UUID        NOT NULL UNIQUE REFERENCES identity.account (id),
    email            TEXT        NOT NULL UNIQUE CHECK (email = lower(email) AND char_length(email) BETWEEN 3 AND 254),
    display_name     TEXT        NOT NULL CHECK (char_length(display_name) BETWEEN 1 AND 100),
    token_hash       TEXT        NOT NULL UNIQUE CHECK (token_hash ~ '^[0-9a-f]{64}$'),
    totp_key_version INTEGER CHECK (totp_key_version > 0),
    totp_nonce       BYTEA,
    totp_ciphertext  BYTEA,
    created_by       UUID        NOT NULL REFERENCES identity.account (id),
    created_at       TIMESTAMPTZ NOT NULL,
    expires_at       TIMESTAMPTZ NOT NULL,
    completed_at     TIMESTAMPTZ,
    CHECK ((totp_key_version IS NULL) = (totp_ciphertext IS NULL) AND (totp_nonce IS NULL) = (totp_ciphertext IS NULL))
);

GRANT SELECT, INSERT, UPDATE, DELETE ON identity.administrator_enrolment TO ${runtime_role};
