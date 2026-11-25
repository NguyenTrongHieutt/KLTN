-- FR-87, AD-48 (Story 25.1): a Milestone keeps how many days late its current handover came against the due date in
-- force at that moment (0 when on time), so the progress page can say so without asking delivery. M2 writes it at
-- every handover; first_days_late, which FR-62's on-time figure reads, is written once and is not touched here.
-- Milestones handed over before this migration keep NULL: their handover's lateness was never recorded, and the
-- page shows nothing for them.
-- Personal data: none.

ALTER TABLE job.milestone
    ADD COLUMN days_late INTEGER CHECK (days_late IS NULL OR days_late >= 0);
