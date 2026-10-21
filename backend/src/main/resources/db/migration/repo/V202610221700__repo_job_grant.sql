-- Story 7.3 (FR-18, FR-75, AD-11, NFR-6): each person's access to a Job's Working Repository. A grant is
-- INVITING while its read-only invitation is being sent, INVITED until GitHub shows them as a
-- collaborator, ACCEPTED once they accepted, and WRITE once raised in place and read back. MEMBER_DRIFT is
-- an invitation GitHub answered 204 (an organization member): never renewed or re-sent. Each renewal of a
-- pending invitation counts; a Crew change member's stops at 3. Every change is audited.
-- Personal data: the person's account and GitHub username; exported for them, deleted on their erasure
-- and with the Product (RepoPersonalData).

CREATE TABLE repo.job_grant
(
    id                   UUID PRIMARY KEY,
    job_id               UUID        NOT NULL,
    product_id           UUID        NOT NULL,
    account_id           UUID        NOT NULL,
    role                 TEXT        NOT NULL CHECK (role IN ('MENTOR', 'CREW')),
    github_login         TEXT        NOT NULL CHECK (github_login ~ '^[A-Za-z0-9-]{1,39}$'),
    repository_github_id BIGINT      NOT NULL,
    repository_name      TEXT        NOT NULL CHECK (length(repository_name) BETWEEN 3 AND 140),
    crew_change          BOOLEAN     NOT NULL,
    status               TEXT        NOT NULL CHECK (status IN ('INVITING', 'INVITED', 'ACCEPTED', 'MEMBER_DRIFT', 'WRITE')),
    two_factor_missing   BOOLEAN     NOT NULL DEFAULT FALSE,
    invitation_id        BIGINT,
    invited_at           TIMESTAMPTZ,
    expires_at           TIMESTAMPTZ,
    renewals             INTEGER     NOT NULL DEFAULT 0 CHECK (renewals >= 0),
    accepted_at          TIMESTAMPTZ,
    write_at             TIMESTAMPTZ,
    created_at           TIMESTAMPTZ NOT NULL,
    UNIQUE (job_id, account_id, repository_github_id),
    CHECK ((status = 'WRITE') = (write_at IS NOT NULL)),
    CHECK (status NOT IN ('ACCEPTED', 'WRITE') OR accepted_at IS NOT NULL),
    CHECK (status <> 'INVITED' OR (invitation_id IS NOT NULL AND expires_at IS NOT NULL))
);

CREATE INDEX job_grant_job_idx ON repo.job_grant (job_id);

CREATE INDEX job_grant_open_idx ON repo.job_grant (status) WHERE status IN ('INVITING', 'INVITED', 'ACCEPTED', 'MEMBER_DRIFT');

CREATE INDEX job_grant_repository_idx ON repo.job_grant (repository_github_id);

CREATE INDEX job_grant_account_idx ON repo.job_grant (account_id);

CREATE INDEX job_grant_product_idx ON repo.job_grant (product_id);

GRANT SELECT, INSERT, UPDATE, DELETE ON repo.job_grant TO ${runtime_role};
