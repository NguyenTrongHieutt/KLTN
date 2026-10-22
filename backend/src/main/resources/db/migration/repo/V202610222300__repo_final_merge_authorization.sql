-- Story 8.7 (FR-37, FR-67, AD-10): a Code Job's Final Merge authorization and the Merge App
-- installations it binds to. GitHub allows one installation per account, so an authorization binds to
-- the pair (installation, repository). It opens REQUESTED at the first closure proposal or at J8, becomes
-- BOUND when an installation passes AD-10's checks, USED once the merge is read back (Story 8.8), and
-- ENDED otherwise. The install link's single-use state is kept only as its SHA-256.
-- Personal data: the Customer's account ID; erasure clears it (RepoPersonalData).

CREATE TABLE repo.merge_installation
(
    installation_id    BIGINT PRIMARY KEY,
    account_github_id  BIGINT      NOT NULL,
    account_login      TEXT        NOT NULL,
    status             TEXT        NOT NULL CHECK (status IN ('ACTIVE', 'REMOVING', 'REMOVED')),
    seen_at            TIMESTAMPTZ NOT NULL,
    removed_at         TIMESTAMPTZ,
    CHECK ((status = 'REMOVED') = (removed_at IS NOT NULL))
);

CREATE TABLE repo.final_merge_authorization
(
    id                   UUID PRIMARY KEY,
    job_id               UUID        NOT NULL,
    product_id           UUID        NOT NULL,
    customer_account_id  UUID,
    repository_github_id BIGINT      NOT NULL,
    repository_name      TEXT        NOT NULL,
    status               TEXT        NOT NULL CHECK (status IN ('REQUESTED', 'BOUND', 'USED', 'ENDED')),
    installation_id      BIGINT REFERENCES repo.merge_installation (installation_id),
    state_hash           TEXT,
    state_expires_at     TIMESTAMPTZ,
    deadline_at          TIMESTAMPTZ,
    refusal              TEXT CHECK (refusal IN ('MERGE_APP_WRONG_REPOSITORY')),
    refused_at           TIMESTAMPTZ,
    request_org          TEXT,
    request_seen_at      TIMESTAMPTZ,
    request_refused_at   TIMESTAMPTZ,
    requested_at         TIMESTAMPTZ NOT NULL,
    bound_at             TIMESTAMPTZ,
    ended_at             TIMESTAMPTZ,
    end_reason           TEXT CHECK (end_reason IN ('PROPOSAL_ENDED', 'CANCELLED', 'DEADLINE', 'DECLINED', 'ZIP_FALLBACK')),
    CHECK ((status = 'BOUND') <= (installation_id IS NOT NULL)),
    CHECK ((status = 'ENDED') = (end_reason IS NOT NULL)),
    CHECK ((status IN ('USED', 'ENDED')) = (ended_at IS NOT NULL))
);

-- One open authorization per Source Repository (AD-10) and per Job.
CREATE UNIQUE INDEX final_merge_authorization_open_repository ON repo.final_merge_authorization (repository_github_id)
    WHERE status IN ('REQUESTED', 'BOUND');
CREATE UNIQUE INDEX final_merge_authorization_open_job ON repo.final_merge_authorization (job_id)
    WHERE status IN ('REQUESTED', 'BOUND');
CREATE UNIQUE INDEX final_merge_authorization_state ON repo.final_merge_authorization (state_hash)
    WHERE state_hash IS NOT NULL;
CREATE INDEX final_merge_authorization_installation ON repo.final_merge_authorization (installation_id)
    WHERE installation_id IS NOT NULL;
CREATE INDEX final_merge_authorization_customer ON repo.final_merge_authorization (customer_account_id)
    WHERE customer_account_id IS NOT NULL;

GRANT SELECT, INSERT, UPDATE ON repo.merge_installation TO ${runtime_role};
GRANT SELECT, INSERT, UPDATE, DELETE ON repo.final_merge_authorization TO ${runtime_role};
