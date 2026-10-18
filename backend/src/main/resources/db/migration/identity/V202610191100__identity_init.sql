-- Schema identity: accounts, their acknowledgments, and the kept Customer sign-in token (AD-19, AD-22).
-- Owned by the migration role; the runtime role ${runtime_role} gets DML table by table.

CREATE SCHEMA identity;

GRANT USAGE ON SCHEMA identity TO ${runtime_role};

-- One account per GitHub numeric user ID, with one permanent role (FR-1). Administrators sign in on
-- their own path and have no GitHub ID (FR-2). A Mentor carries an approval status (FR-74). An erased
-- account keeps its row, with its personal fields cleared (AD-24).
CREATE TABLE identity.account
(
    id                     UUID PRIMARY KEY,
    github_user_id         BIGINT UNIQUE,
    github_login           TEXT,
    github_avatar_url      TEXT,
    role                   TEXT        NOT NULL CHECK (role IN ('CUSTOMER', 'MENTOR', 'STUDENT', 'ADMINISTRATOR')),
    mentor_approval_status TEXT CHECK (mentor_approval_status IN ('PENDING', 'APPROVED', 'REJECTED', 'REVOKED')),
    locked_at              TIMESTAMPTZ,
    lock_reason            TEXT,
    erased_at              TIMESTAMPTZ,
    created_at             TIMESTAMPTZ NOT NULL,
    signed_in_at           TIMESTAMPTZ,
    CHECK ((role = 'MENTOR') = (mentor_approval_status IS NOT NULL)),
    CHECK (role = 'ADMINISTRATOR' OR erased_at IS NOT NULL OR github_user_id IS NOT NULL),
    CHECK (role <> 'ADMINISTRATOR' OR github_user_id IS NULL),
    CHECK ((locked_at IS NULL) = (lock_reason IS NULL))
);

GRANT SELECT, INSERT, UPDATE ON identity.account TO ${runtime_role};

-- What an account acknowledged, with the notice version and time (AD-19, NFR-9).
CREATE TABLE identity.acknowledgment
(
    id              UUID PRIMARY KEY,
    account_id      UUID        NOT NULL REFERENCES identity.account (id),
    notice          TEXT        NOT NULL CHECK (notice IN ('PRIVACY')),
    notice_version  TEXT        NOT NULL,
    acknowledged_at TIMESTAMPTZ NOT NULL,
    UNIQUE (account_id, notice, notice_version)
);

GRANT SELECT, INSERT ON identity.acknowledgment TO ${runtime_role};

-- The one kept sign-in token, a Customer's, for the AD-13 re-checks: AES-GCM under the versioned
-- keyring with the account ID as associated data (AD-22, SG-9). Never returned by any API.
CREATE TABLE identity.customer_token
(
    account_id  UUID PRIMARY KEY REFERENCES identity.account (id),
    key_version INT         NOT NULL CHECK (key_version > 0),
    nonce       BYTEA       NOT NULL,
    ciphertext  BYTEA       NOT NULL,
    stored_at   TIMESTAMPTZ NOT NULL
);

GRANT SELECT, INSERT, UPDATE, DELETE ON identity.customer_token TO ${runtime_role};
