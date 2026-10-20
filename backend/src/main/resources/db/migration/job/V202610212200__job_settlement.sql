-- M8 (FR-54, AD-31): a Held Milestone settled by an Administrator's decision records when, the release
-- and refund totals, and whether the handover was merged.

ALTER TABLE job.milestone
    ADD COLUMN settled_at         TIMESTAMPTZ,
    ADD COLUMN settled_release_vnd BIGINT CHECK (settled_release_vnd >= 0),
    ADD COLUMN settled_refund_vnd  BIGINT CHECK (settled_refund_vnd >= 0),
    ADD CONSTRAINT milestone_settled_check CHECK ((state = 'SETTLED') = (settled_at IS NOT NULL));
