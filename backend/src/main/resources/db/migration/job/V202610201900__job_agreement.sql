-- FR-16, AD-29: the Agreement draft. J3 records the selected quote; J4 sets the Agreement deadline
-- (FR-40: 14 days in Agreement) and starts the draft. Milestones, Acceptance Criteria, and External
-- Access entries are live entities whose UUIDs are created once and never re-keyed; a removed one is
-- marked, never deleted. Sharing writes an immutable Agreement version: a snapshot of the live entities
-- by ID, with the Skill names of that moment. Payment shares never appear here (Crew Terms, AD-41).
-- Personal data: none beyond the Job's own records; a Product's deletion deletes them (JobPersonalData).

ALTER TABLE job.job
    ADD COLUMN quote_price_vnd     BIGINT CHECK (quote_price_vnd > 0),
    ADD COLUMN quote_timeline_days INTEGER CHECK (quote_timeline_days > 0),
    ADD COLUMN agreement_due_at    TIMESTAMPTZ;

CREATE TABLE job.agreement_draft
(
    job_id      UUID PRIMARY KEY REFERENCES job.job (id),
    scope       TEXT        NOT NULL DEFAULT '' CHECK (char_length(scope) <= 4000),
    price_vnd   BIGINT CHECK (price_vnd BETWEEN 1000 AND 1000000000),
    skill_ids   UUID[]      NOT NULL DEFAULT '{}',
    skill_names TEXT[]      NOT NULL DEFAULT '{}',
    revision    INTEGER     NOT NULL DEFAULT 0 CHECK (revision >= 0),
    saved_at    TIMESTAMPTZ NOT NULL,
    CHECK (cardinality(skill_ids) <= 10 AND cardinality(skill_ids) = cardinality(skill_names))
);

GRANT SELECT, INSERT, UPDATE, DELETE ON job.agreement_draft TO ${runtime_role};

CREATE TABLE job.milestone
(
    id              UUID PRIMARY KEY,
    job_id          UUID        NOT NULL REFERENCES job.job (id),
    position        INTEGER     NOT NULL CHECK (position >= 0),
    title           TEXT        NOT NULL DEFAULT '' CHECK (char_length(title) <= 120),
    price_vnd       BIGINT CHECK (price_vnd BETWEEN 1000 AND 1000000000),
    due_on          DATE,
    revision_rounds INTEGER     NOT NULL CHECK (revision_rounds BETWEEN 0 AND 10),
    created_at      TIMESTAMPTZ NOT NULL,
    removed_at      TIMESTAMPTZ
);

CREATE INDEX milestone_job_idx ON job.milestone (job_id, position) WHERE removed_at IS NULL;

GRANT SELECT, INSERT, UPDATE, DELETE ON job.milestone TO ${runtime_role};

CREATE TABLE job.acceptance_criterion
(
    id           UUID PRIMARY KEY,
    job_id       UUID        NOT NULL REFERENCES job.job (id),
    milestone_id UUID        NOT NULL REFERENCES job.milestone (id),
    position     INTEGER     NOT NULL CHECK (position >= 0),
    statement    TEXT        NOT NULL DEFAULT '' CHECK (char_length(statement) <= 500),
    check_steps  TEXT        NOT NULL DEFAULT '' CHECK (char_length(check_steps) <= 2000),
    created_at   TIMESTAMPTZ NOT NULL,
    removed_at   TIMESTAMPTZ
);

CREATE INDEX acceptance_criterion_milestone_idx ON job.acceptance_criterion (milestone_id, position)
    WHERE removed_at IS NULL;

CREATE INDEX acceptance_criterion_job_idx ON job.acceptance_criterion (job_id);

GRANT SELECT, INSERT, UPDATE, DELETE ON job.acceptance_criterion TO ${runtime_role};

CREATE TABLE job.external_access
(
    id           UUID PRIMARY KEY,
    job_id       UUID        NOT NULL REFERENCES job.job (id),
    position     INTEGER     NOT NULL CHECK (position >= 0),
    system       TEXT        NOT NULL DEFAULT '' CHECK (char_length(system) <= 120),
    purpose      TEXT        NOT NULL DEFAULT '' CHECK (char_length(purpose) <= 300),
    environment  TEXT        NOT NULL DEFAULT 'NON_PRODUCTION' CHECK (environment IN ('NON_PRODUCTION', 'PRODUCTION')),
    access_level TEXT        NOT NULL DEFAULT 'READ' CHECK (access_level IN ('READ', 'WRITE', 'ADMIN')),
    created_at   TIMESTAMPTZ NOT NULL,
    removed_at   TIMESTAMPTZ
);

CREATE INDEX external_access_job_idx ON job.external_access (job_id, position) WHERE removed_at IS NULL;

GRANT SELECT, INSERT, UPDATE, DELETE ON job.external_access TO ${runtime_role};

CREATE TABLE job.agreement_version
(
    job_id            UUID        NOT NULL REFERENCES job.job (id),
    version_no        INTEGER     NOT NULL CHECK (version_no > 0),
    status            TEXT        NOT NULL CHECK (status IN ('SHARED')),
    content           JSONB       NOT NULL,
    author_account_id UUID        NOT NULL,
    created_at        TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (job_id, version_no)
);

GRANT SELECT, INSERT, DELETE ON job.agreement_version TO ${runtime_role};
