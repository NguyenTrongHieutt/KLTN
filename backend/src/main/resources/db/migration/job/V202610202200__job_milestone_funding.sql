-- FR-41, FR-42, AD-4, AD-41: Milestones get the PRD section 4.0 states, which only JobLifecycle writes.
-- M1 records when the Milestone was funded and the Crew Terms version in force at that moment, the pin
-- its release uses. J6 lets one Code Job of a Product run at a time; this partial unique index backs
-- the advisory-locked check up.
-- Personal data: none beyond the Job's own records.

ALTER TABLE job.milestone
    ADD COLUMN state                 TEXT NOT NULL DEFAULT 'PLANNED'
        CHECK (state IN ('PLANNED', 'FUNDED', 'REVISION_REQUESTED', 'SUBMITTED', 'ACCEPTED', 'CANCELLED', 'SETTLED')),
    ADD COLUMN funded_at             TIMESTAMPTZ,
    ADD COLUMN crew_terms_version_no INTEGER CHECK (crew_terms_version_no > 0),
    ADD CONSTRAINT milestone_funded_pinned CHECK (state = 'PLANNED' OR state = 'CANCELLED'
        OR (funded_at IS NOT NULL AND crew_terms_version_no IS NOT NULL));

CREATE UNIQUE INDEX job_code_job_running_idx ON job.job (product_id)
    WHERE state IN ('STARTING', 'ACTIVE', 'CLOSING') AND job_types <> ARRAY ['ASSESSMENT']::TEXT[];
