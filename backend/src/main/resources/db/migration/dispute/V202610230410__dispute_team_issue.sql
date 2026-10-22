-- FR-79, AD-40 (Tier 3): a Crew member's Team Issue with the Job's Mentor about their share of a Held
-- Milestone (SHARE) or the Rating the Mentor gave them (RATING), kept apart from Disputes. It never freezes
-- the Job, and the Customer never sees it. The phase moves ANSWER, ESCALATION, ESCALATED, ENDED; the
-- periods are the Job's copied FR-61 values at the raise. One issue per raiser per Job that has not ended
-- (TEAM_ISSUE_OPEN), one SHARE issue per raiser per Milestone, and one issue per Rating (both
-- TEAM_ISSUE_ALREADY_RAISED), so a decided matter is never raised again.
-- Personal data: exported for its raiser; the raiser and the Mentor are cleared on erasure, which an issue
-- not ended blocks; deleted with the Product (DisputePersonalData).

CREATE TABLE dispute.team_issue
(
    id                UUID PRIMARY KEY,
    job_id            UUID        NOT NULL,
    product_id        UUID        NOT NULL,
    subject           TEXT        NOT NULL CHECK (subject IN ('SHARE', 'RATING')),
    milestone_id      UUID,
    milestone_number  INTEGER CHECK (milestone_number > 0),
    rating_id         UUID,
    raiser_account_id UUID,
    mentor_account_id UUID,
    reason            TEXT        NOT NULL CHECK (char_length(reason) BETWEEN 1 AND 2000),
    phase             TEXT        NOT NULL CHECK (phase IN ('ANSWER', 'ESCALATION', 'ESCALATED', 'ENDED')),
    answer_days       INTEGER     NOT NULL CHECK (answer_days BETWEEN 1 AND 30),
    escalation_days   INTEGER     NOT NULL CHECK (escalation_days BETWEEN 1 AND 30),
    raised_at         TIMESTAMPTZ NOT NULL,
    answer_due_at     TIMESTAMPTZ NOT NULL,
    escalation_due_at TIMESTAMPTZ,
    ended_at          TIMESTAMPTZ,
    CHECK ((subject = 'SHARE') = (milestone_id IS NOT NULL)),
    CHECK ((milestone_id IS NULL) = (milestone_number IS NULL)),
    CHECK ((subject = 'RATING') = (rating_id IS NOT NULL)),
    CHECK ((phase = 'ENDED') = (ended_at IS NOT NULL))
);

CREATE UNIQUE INDEX team_issue_open_idx ON dispute.team_issue (job_id, raiser_account_id) WHERE phase <> 'ENDED';

CREATE UNIQUE INDEX team_issue_share_idx ON dispute.team_issue (milestone_id, raiser_account_id) WHERE subject = 'SHARE';

CREATE UNIQUE INDEX team_issue_rating_idx ON dispute.team_issue (rating_id) WHERE subject = 'RATING';

CREATE INDEX team_issue_mentor_idx ON dispute.team_issue (mentor_account_id);

CREATE INDEX team_issue_product_idx ON dispute.team_issue (product_id);

GRANT SELECT, INSERT, DELETE ON dispute.team_issue TO ${runtime_role};

GRANT UPDATE (phase, escalation_due_at, ended_at, raiser_account_id, mentor_account_id) ON dispute.team_issue
    TO ${runtime_role};
