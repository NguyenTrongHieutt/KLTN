-- The Job lifecycle (PRD section 4.0, AD-4). JobLifecycle.apply is the only writer of state and bumps
-- version on every transition. J1 copies the FR-61 rule values (rules) and the fixed 14-day selection
-- period of addendum B2 (selection_days) onto the Job, so a later settings change never alters it
-- (AD-7); J2 clears them with the Job Post. opened_at and lapses_at describe the Open Job Post.
-- frozen_at is set while a Dispute is open (FR-52); agreement_version_no names the confirmed
-- Agreement version once there is one (FR-17).

ALTER TABLE job.job
    ADD COLUMN version              BIGINT  NOT NULL DEFAULT 0 CHECK (version >= 0),
    ADD COLUMN rules                JSONB,
    ADD COLUMN selection_days       INTEGER CHECK (selection_days > 0),
    ADD COLUMN opened_at            TIMESTAMPTZ,
    ADD COLUMN lapses_at            TIMESTAMPTZ,
    ADD COLUMN frozen_at            TIMESTAMPTZ,
    ADD COLUMN agreement_version_no INTEGER CHECK (agreement_version_no > 0),
    ADD CONSTRAINT job_draft_check CHECK (state <> 'DRAFT'
        OR (rules IS NULL AND selection_days IS NULL AND opened_at IS NULL AND lapses_at IS NULL)),
    ADD CONSTRAINT job_published_check CHECK (state = 'DRAFT' OR (rules IS NOT NULL AND selection_days IS NOT NULL)),
    ADD CONSTRAINT job_open_check CHECK (state <> 'OPEN' OR (opened_at IS NOT NULL AND lapses_at > opened_at));
