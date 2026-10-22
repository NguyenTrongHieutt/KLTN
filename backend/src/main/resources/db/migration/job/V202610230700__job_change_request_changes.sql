-- FR-36, AD-29 (Story 14.1): what a Change Request changes beyond due dates: the scope, Milestone titles and
-- prices, added and removed Milestones, and added, edited, and removed Acceptance Criteria. A request made
-- before it changes nothing more ('{}'). Never a share or the Crew.

ALTER TABLE job.change_request
    ADD COLUMN changes JSONB NOT NULL DEFAULT '{}'::jsonb;
