-- FR-32, AD-4: M2 records the evidence delivery vouches for, the Milestone Handover's ID, and when it was
-- made. A later handover of the same Milestone, after a revision, records its own.
-- Personal data: none beyond the Job's own records.

ALTER TABLE job.milestone
    ADD COLUMN submitted_at TIMESTAMPTZ,
    ADD COLUMN handover_id  UUID,
    ADD CONSTRAINT milestone_handover_check CHECK ((submitted_at IS NULL) = (handover_id IS NULL)),
    ADD CONSTRAINT milestone_submitted_check CHECK (state <> 'SUBMITTED' OR handover_id IS NOT NULL);
