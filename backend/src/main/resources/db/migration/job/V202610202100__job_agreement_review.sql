-- FR-16, FR-17, AD-29, AD-41 (revised 2026-10-20, R-1): the Customer's review of a shared draft and
-- the confirmed Agreement. Comments and change requests name the shared draft version they are about;
-- a share resolves the open change requests. An approval names its version, so a later share leaves
-- it behind and a Crew change leaves it standing (R-1 M5). The Mentor's confirmation writes the
-- confirmed Agreement: an insert-only snapshot of the approved content, the confirmers, the live-system
-- choices, and the Crew's GitHub usernames at that moment, never a share or a Crew Terms version.
-- Personal data: Job records; deleted with the Product, and with the Job's Customer (JobPersonalData).

CREATE TABLE job.agreement_comment
(
    id                UUID PRIMARY KEY,
    job_id            UUID        NOT NULL,
    version_no        INTEGER     NOT NULL,
    target_kind       TEXT        NOT NULL CHECK (target_kind IN ('SCOPE', 'MILESTONE', 'CRITERION', 'EXTERNAL_ACCESS')),
    target_id         UUID,
    author_account_id UUID        NOT NULL,
    body              TEXT        NOT NULL CHECK (char_length(body) BETWEEN 1 AND 1000),
    created_at        TIMESTAMPTZ NOT NULL,
    FOREIGN KEY (job_id, version_no) REFERENCES job.agreement_version (job_id, version_no),
    CHECK ((target_kind = 'SCOPE') = (target_id IS NULL))
);

CREATE INDEX agreement_comment_version_idx ON job.agreement_comment (job_id, version_no, created_at);

GRANT SELECT, INSERT, DELETE ON job.agreement_comment TO ${runtime_role};

CREATE TABLE job.agreement_change_request
(
    id                   UUID PRIMARY KEY,
    job_id               UUID        NOT NULL,
    version_no           INTEGER     NOT NULL,
    customer_account_id  UUID        NOT NULL,
    note                 TEXT        NOT NULL CHECK (char_length(note) BETWEEN 1 AND 2000),
    milestone_order      UUID[],
    created_at           TIMESTAMPTZ NOT NULL,
    resolved_at          TIMESTAMPTZ,
    FOREIGN KEY (job_id, version_no) REFERENCES job.agreement_version (job_id, version_no)
);

CREATE INDEX agreement_change_request_open_idx ON job.agreement_change_request (job_id) WHERE resolved_at IS NULL;

GRANT SELECT, INSERT, UPDATE, DELETE ON job.agreement_change_request TO ${runtime_role};

CREATE TABLE job.agreement_approval
(
    job_id              UUID        NOT NULL,
    version_no          INTEGER     NOT NULL,
    customer_account_id UUID        NOT NULL,
    freeze_committed    BOOLEAN     NOT NULL,
    live_access_ids     UUID[]      NOT NULL DEFAULT '{}',
    approved_at         TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (job_id, version_no),
    FOREIGN KEY (job_id, version_no) REFERENCES job.agreement_version (job_id, version_no)
);

GRANT SELECT, INSERT, DELETE ON job.agreement_approval TO ${runtime_role};

CREATE TABLE job.confirmed_agreement
(
    job_id              UUID        NOT NULL REFERENCES job.job (id),
    version_no          INTEGER     NOT NULL CHECK (version_no > 0),
    draft_version_no    INTEGER     NOT NULL,
    content             JSONB       NOT NULL,
    crew_usernames      TEXT[]      NOT NULL,
    live_access_ids     UUID[]      NOT NULL DEFAULT '{}',
    freeze_committed    BOOLEAN     NOT NULL,
    customer_account_id UUID        NOT NULL,
    approved_at         TIMESTAMPTZ NOT NULL,
    mentor_account_id   UUID        NOT NULL,
    confirmed_at        TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (job_id, version_no),
    FOREIGN KEY (job_id, draft_version_no) REFERENCES job.agreement_version (job_id, version_no)
);

GRANT SELECT, INSERT, DELETE ON job.confirmed_agreement TO ${runtime_role};
