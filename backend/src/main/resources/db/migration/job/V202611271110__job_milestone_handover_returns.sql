-- FR-86, AD-4 M3, AD-47 exception 2 (Story 25.5): how many times the Customer of a Self-mentored Job returned this
-- Milestone's handover. M3 by a return adds one and refuses once the count reaches the Job's copied
-- HANDOVER_RETURN_LIMIT (job.job.rules, default 2). A normal Job never returns a handover, so its Milestones keep 0;
-- an M3 after an acceptance merge's 409 counts nothing.
-- Personal data: none.

ALTER TABLE job.milestone
    ADD COLUMN handover_returns_used INTEGER NOT NULL DEFAULT 0 CHECK (handover_returns_used >= 0);
