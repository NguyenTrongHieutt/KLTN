-- FR-79, AD-40 (Story 12.2): the Mentor's one answer, which moves no money (KEEP; TRANSFER of whole
-- percentage points from the Mentor's pinned share to the raiser's; WITHDRAW_RATING), the escalation, and
-- the issue's one ending with its outcome: the raiser accepted the answer (ACCEPTED), the escalation period
-- lapsed (LAPSED), an Administrator decided (DECIDED, Story 12.3), or it became moot (MOOT).

ALTER TABLE dispute.team_issue
    ADD COLUMN answer_kind            TEXT CHECK (answer_kind IN ('KEEP', 'TRANSFER', 'WITHDRAW_RATING')),
    ADD COLUMN answer_points          INTEGER CHECK (answer_points BETWEEN 0 AND 100),
    ADD COLUMN answered_at            TIMESTAMPTZ,
    ADD COLUMN escalated_at           TIMESTAMPTZ,
    ADD COLUMN ending                 TEXT CHECK (ending IN ('ACCEPTED', 'LAPSED', 'DECIDED', 'MOOT')),
    ADD COLUMN outcome_points         INTEGER CHECK (outcome_points BETWEEN 0 AND 100),
    ADD COLUMN outcome_rating_removed BOOLEAN,
    ADD CONSTRAINT team_issue_answered_check CHECK ((answer_kind IS NULL) = (answered_at IS NULL)),
    ADD CONSTRAINT team_issue_ended_check CHECK ((ending IS NULL) = (ended_at IS NULL));

GRANT UPDATE (answer_kind, answer_points, answered_at, escalated_at, ending, outcome_points, outcome_rating_removed)
    ON dispute.team_issue TO ${runtime_role};
