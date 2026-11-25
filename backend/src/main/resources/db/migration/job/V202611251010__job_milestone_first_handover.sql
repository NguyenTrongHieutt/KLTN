-- FR-87, FR-62, AD-48 (Story 25.1): a Milestone keeps when its first handover came and how many days late it was
-- against the due date in force at that moment (0 when on time). M2 writes both once and never again, so a later
-- handover, or a due date a rework or late funding moved, cannot turn a late Milestone into an on-time one. FR-62's
-- on-time figure reads first_days_late. Milestones handed over before this migration keep the best record there is:
-- their last submission time against their due date, which is what the figure compared until now.
-- Personal data: none.

ALTER TABLE job.milestone
    ADD COLUMN first_submitted_at TIMESTAMPTZ,
    ADD COLUMN first_days_late    INTEGER,
    ADD CONSTRAINT milestone_first_handover_check CHECK (
        (first_submitted_at IS NULL) = (first_days_late IS NULL) AND (first_days_late IS NULL OR first_days_late >= 0));

UPDATE job.milestone
SET first_submitted_at = submitted_at,
    first_days_late    = CASE
        WHEN due_on IS NULL THEN 0
        ELSE GREATEST(0, CEIL(EXTRACT(EPOCH FROM (submitted_at - ((due_on + 1)::timestamp AT TIME ZONE 'Asia/Ho_Chi_Minh')))
            / 86400.0))::INTEGER
    END
WHERE submitted_at IS NOT NULL;
