-- FR-79, AD-40, AD-20 (Story 12.3): the Administrator who took an escalated Team Issue and the read grant
-- pushed for them, and the decision, which is final: the points moved from the Mentor's pinned share or the
-- Rating kept or removed, with the written reasoning. NFR-10 keeps the decided record (SG-27).
-- Personal data: the Administrator is cleared on erasure (DisputePersonalData).

ALTER TABLE dispute.team_issue
    ADD COLUMN assigned_admin_id       UUID,
    ADD COLUMN assigned_at             TIMESTAMPTZ,
    ADD COLUMN grant_id                UUID,
    ADD COLUMN decided_by_admin_id     UUID,
    ADD COLUMN decision_points         INTEGER CHECK (decision_points BETWEEN 0 AND 100),
    ADD COLUMN decision_rating_removed BOOLEAN,
    ADD COLUMN decision_reasoning      TEXT CHECK (char_length(decision_reasoning) BETWEEN 1 AND 4000),
    ADD COLUMN decided_at              TIMESTAMPTZ,
    ADD CONSTRAINT team_issue_decided_check CHECK ((decided_at IS NULL) = (decision_reasoning IS NULL));

CREATE INDEX team_issue_escalated_idx ON dispute.team_issue (escalated_at) WHERE escalated_at IS NOT NULL;

GRANT UPDATE (assigned_admin_id, assigned_at, grant_id, decided_by_admin_id, decision_points, decision_rating_removed,
    decision_reasoning, decided_at) ON dispute.team_issue TO ${runtime_role};
