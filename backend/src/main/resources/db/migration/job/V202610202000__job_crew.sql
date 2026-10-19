-- FR-75, AD-30, AD-41 (revised 2026-10-20, R-1): the Crew is the Mentor plus Crew rows in job. A row is
-- ASSIGNED when added, ACTIVE once its Student signed the NDA and confirmed the latest Crew Terms
-- version, INACTIVE after a lock, and RELEASED by a decline, a removal, or the Job's end; one running
-- Crew per Student. Crew Terms are immutable versions of whole-percentage shares (the Mentor's capped
-- by settings at the save) with append-only confirmations. Every Crew write takes the Job lock and
-- increments crew_version. crew_reconfirm_owed_at is set when a row is locked between the Agreement
-- confirmation and J6 (R-1 H1, Story 4.4). Shares never reach a Customer (AD-20).
-- Personal data: Job records; deleted with the Product, and with the Job's Customer (JobPersonalData).

ALTER TABLE job.job
    ADD COLUMN crew_version           BIGINT NOT NULL DEFAULT 0 CHECK (crew_version >= 0),
    ADD COLUMN crew_reconfirm_owed_at TIMESTAMPTZ;

CREATE TABLE job.crew_row
(
    id                 UUID PRIMARY KEY,
    job_id             UUID        NOT NULL REFERENCES job.job (id),
    student_account_id UUID        NOT NULL,
    status             TEXT        NOT NULL CHECK (status IN ('ASSIGNED', 'ACTIVE', 'INACTIVE', 'RELEASED')),
    signed_at          TIMESTAMPTZ,
    activated_at       TIMESTAMPTZ,
    window_end         TIMESTAMPTZ,
    released_at        TIMESTAMPTZ,
    released_reason    TEXT CHECK (released_reason IN ('DECLINED', 'REMOVED', 'JOB_ENDED')),
    created_at         TIMESTAMPTZ NOT NULL,
    CHECK ((status = 'RELEASED') = (released_at IS NOT NULL AND released_reason IS NOT NULL)),
    CHECK (status <> 'ACTIVE' OR (signed_at IS NOT NULL AND activated_at IS NOT NULL))
);

CREATE UNIQUE INDEX crew_row_running_idx ON job.crew_row (student_account_id) WHERE status <> 'RELEASED';

CREATE INDEX crew_row_job_idx ON job.crew_row (job_id, created_at);

GRANT SELECT, INSERT, UPDATE, DELETE ON job.crew_row TO ${runtime_role};

CREATE TABLE job.crew_terms_version
(
    job_id            UUID        NOT NULL REFERENCES job.job (id),
    version_no        INTEGER     NOT NULL CHECK (version_no > 0),
    status            TEXT        NOT NULL CHECK (status IN ('PENDING', 'IN_FORCE', 'SUPERSEDED', 'VOIDED')),
    cap_percent       INTEGER     NOT NULL CHECK (cap_percent BETWEEN 0 AND 100),
    author_account_id UUID        NOT NULL,
    created_at        TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (job_id, version_no)
);

GRANT SELECT, INSERT, UPDATE, DELETE ON job.crew_terms_version TO ${runtime_role};

CREATE TABLE job.crew_terms_share
(
    job_id           UUID    NOT NULL,
    version_no       INTEGER NOT NULL,
    party_account_id UUID    NOT NULL,
    crew_row_id      UUID REFERENCES job.crew_row (id),
    percent          INTEGER NOT NULL CHECK (percent BETWEEN 0 AND 100),
    PRIMARY KEY (job_id, version_no, party_account_id),
    FOREIGN KEY (job_id, version_no) REFERENCES job.crew_terms_version (job_id, version_no)
);

GRANT SELECT, INSERT, DELETE ON job.crew_terms_share TO ${runtime_role};

CREATE TABLE job.crew_terms_confirmation
(
    job_id       UUID        NOT NULL,
    version_no   INTEGER     NOT NULL,
    crew_row_id  UUID        NOT NULL REFERENCES job.crew_row (id),
    confirmed_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (job_id, version_no, crew_row_id),
    FOREIGN KEY (job_id, version_no) REFERENCES job.crew_terms_version (job_id, version_no)
);

GRANT SELECT, INSERT, DELETE ON job.crew_terms_confirmation TO ${runtime_role};
