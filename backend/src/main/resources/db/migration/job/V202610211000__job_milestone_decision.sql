-- FR-33, FR-34, FR-35: the Customer's decision on a handed-over Milestone. M5 records when it was
-- accepted and whether automatically (FR-35); M4 uses one Revision Round, never more than the Milestone
-- has, and records how many criteria the request named.
-- Personal data: none beyond the Job's own records.

ALTER TABLE job.milestone
    ADD COLUMN accepted_at          TIMESTAMPTZ,
    ADD COLUMN auto_accepted        BOOLEAN NOT NULL DEFAULT false,
    ADD COLUMN revision_rounds_used INTEGER NOT NULL DEFAULT 0 CHECK (revision_rounds_used >= 0),
    ADD COLUMN revision_criteria    INTEGER CHECK (revision_criteria > 0),
    ADD CONSTRAINT milestone_rounds_check CHECK (revision_rounds_used <= revision_rounds),
    ADD CONSTRAINT milestone_accepted_check CHECK ((state = 'ACCEPTED') = (accepted_at IS NOT NULL) OR state = 'SETTLED');
