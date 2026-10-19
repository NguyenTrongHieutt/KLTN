-- Schema job (FR-9, AD-2): a Customer's Jobs on their Products. Story 3.1 keeps Drafts: the guided
-- answers, the Job Types, the Skills (IDs with their names, refreshed on SkillRenamed, AD-38), the
-- budget range, the deadline, and the screenshots stored by files. The state column already names the
-- states of PRD section 4.0 that the Job lifecycle (Story 3.2 on) moves through.
-- Personal data: exported, and deleted on erasure and Product deletion (JobPersonalData).

CREATE SCHEMA job;

GRANT USAGE ON SCHEMA job TO ${runtime_role};

CREATE TABLE job.job
(
    id                  UUID PRIMARY KEY,
    product_id          UUID        NOT NULL,
    customer_account_id UUID        NOT NULL,
    state               TEXT        NOT NULL CHECK (state IN ('DRAFT', 'OPEN', 'SELECTING', 'AGREEMENT', 'STARTING',
                                                              'ACTIVE', 'CLOSING', 'CLOSED', 'CANCELLED')),
    title               TEXT        NOT NULL CHECK (char_length(title) BETWEEN 1 AND 120),
    -- The guided answers (FR-9): what is wrong, what should happen, who uses the app, how to check.
    problem             TEXT CHECK (char_length(problem) BETWEEN 1 AND 2000),
    expected            TEXT CHECK (char_length(expected) BETWEEN 1 AND 2000),
    app_users           TEXT CHECK (char_length(app_users) BETWEEN 1 AND 1000),
    done_check          TEXT CHECK (char_length(done_check) BETWEEN 1 AND 1000),
    job_types           TEXT[]      NOT NULL DEFAULT '{}' CHECK (
        job_types <@ ARRAY ['ASSESSMENT', 'BUG_FIX', 'NEW_FEATURE', 'MODIFICATION', 'IMPROVEMENT', 'SECURITY_TESTING',
            'DEPLOYMENT_OPERATIONS', 'DOCUMENTATION']::TEXT[]
        AND (NOT 'ASSESSMENT' = ANY (job_types) OR cardinality(job_types) = 1)),
    skill_ids           UUID[]      NOT NULL DEFAULT '{}',
    skill_names         TEXT[]      NOT NULL DEFAULT '{}',
    budget              TEXT CHECK (budget IN ('UNDER_2M', 'FROM_2M_TO_5M', 'FROM_5M_TO_10M', 'OVER_10M')),
    deadline            DATE,
    created_at          TIMESTAMPTZ NOT NULL,
    updated_at          TIMESTAMPTZ NOT NULL,
    CHECK (cardinality(skill_ids) <= 10 AND cardinality(skill_ids) = cardinality(skill_names))
);

CREATE INDEX job_customer_idx ON job.job (customer_account_id, updated_at DESC);

CREATE INDEX job_product_idx ON job.job (product_id);

-- SkillRenamed finds the Jobs holding a Skill.
CREATE INDEX job_skill_idx ON job.job USING GIN (skill_ids);

-- A Job's screenshots (FR-9, NFR-3): files keeps the bytes; at most 200 MB per Job (AD-34).
CREATE TABLE job.job_screenshot
(
    job_id     UUID        NOT NULL REFERENCES job.job (id) ON DELETE CASCADE,
    file_id    UUID        NOT NULL UNIQUE,
    file_name  TEXT        NOT NULL CHECK (char_length(file_name) BETWEEN 1 AND 255),
    size_bytes BIGINT      NOT NULL CHECK (size_bytes > 0),
    added_at   TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (job_id, file_id)
);

GRANT SELECT, INSERT, UPDATE, DELETE ON job.job, job.job_screenshot TO ${runtime_role};
