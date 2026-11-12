-- Schema github (AD-44, AD-45, AD-46, Story 23.4): the Actions token exchange's correlations. Each is
-- recorded before its run is dispatched, under a random UUID that goes into the run's title, and lets that
-- run trade its GitHub Actions ID token once for a token narrowed to the grant, with exactly the
-- operation's permissions. No token and no JWT is ever kept here: an issued token lives only in the
-- application's memory until it is revoked (AD-22), so a restart leaves REVOKE_UNKNOWN.
-- Personal data: none; repository names and IDs, run IDs, codes and times identify nobody.

CREATE TABLE github.exchange_correlation
(
    id                    UUID PRIMARY KEY,
    operation             TEXT        NOT NULL CHECK (operation IN ('copy', 'final-merge', 'handover-copy')),
    target_repository     TEXT        NOT NULL CHECK (length(target_repository) BETWEEN 3 AND 140),
    target_repository_id  BIGINT      NOT NULL CHECK (target_repository_id > 0),
    target_default_branch TEXT        NOT NULL CHECK (length(target_default_branch) BETWEEN 1 AND 255),
    workflow_path         TEXT        NOT NULL
        CHECK (workflow_path ~ '^\.github/workflows/[A-Za-z0-9][A-Za-z0-9._-]{0,99}\.ya?ml$'),
    dispatching_app       TEXT        NOT NULL CHECK (dispatching_app IN ('PLATFORM', 'SOURCE')),
    grant_repository_ids  JSONB       NOT NULL
        CHECK (jsonb_typeof(grant_repository_ids) = 'array' AND jsonb_array_length(grant_repository_ids) BETWEEN 1 AND 500),
    grant_permissions     JSONB       NOT NULL CHECK (jsonb_typeof(grant_permissions) = 'object'),
    confirmed_head_sha    TEXT CHECK (confirmed_head_sha ~ '^[0-9a-f]{40}$'),
    status                TEXT        NOT NULL
        CHECK (status IN ('OPEN', 'EXCHANGED', 'REFUSED', 'REVOKED', 'REVOKE_UNKNOWN', 'EXPIRED')),
    refusal_reason        TEXT CHECK (refusal_reason IN ('REPOSITORY', 'EVENT', 'RUNNER', 'RUN', 'BLOB', 'SHA', 'REF')),
    run_id                BIGINT CHECK (run_id > 0),
    created_at            TIMESTAMPTZ NOT NULL,
    expires_at            TIMESTAMPTZ NOT NULL,
    issued_at             TIMESTAMPTZ,
    revoked_at            TIMESTAMPTZ,
    CHECK (expires_at > created_at),
    CHECK (confirmed_head_sha IS NULL OR operation = 'copy'),
    CHECK ((status = 'REFUSED') = (refusal_reason IS NOT NULL)),
    CHECK ((status IN ('EXCHANGED', 'REVOKED', 'REVOKE_UNKNOWN')) = (issued_at IS NOT NULL)),
    CHECK (issued_at IS NULL OR run_id IS NOT NULL),
    CHECK ((status = 'REVOKED') = (revoked_at IS NOT NULL))
);

-- The revocation poller reads the correlations still open or holding a token.
CREATE INDEX exchange_correlation_live_idx ON github.exchange_correlation (status)
    WHERE status IN ('OPEN', 'EXCHANGED');

GRANT SELECT, INSERT, UPDATE ON github.exchange_correlation TO ${runtime_role};
