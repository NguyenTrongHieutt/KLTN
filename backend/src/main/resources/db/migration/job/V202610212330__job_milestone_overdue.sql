-- FR-48, AD-33: a held Milestone past its effective due time is flagged overdue, a flag rather than a
-- state, which moves no money; a handover (M2) or a later due date clears it. The due-soon reminder is
-- sent once per due date. Personal data: none beyond the Job's own records.

ALTER TABLE job.milestone
    ADD COLUMN overdue_since     TIMESTAMPTZ,
    ADD COLUMN due_soon_sent_for DATE;

CREATE INDEX milestone_overdue_idx ON job.milestone (overdue_since) WHERE overdue_since IS NOT NULL;
