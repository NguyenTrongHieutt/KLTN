-- Decisions on Mentor applications (FR-74, SG-10): each approval or rejection, by whom and when,
-- with the reason a rejected applicant reads and the day they may submit again. The record stays
-- when a rejected application's fields are deleted 12 months later (AD-24); the application points
-- at its current decision until a resubmission clears it. Personal data: exported, and deleted on
-- the applicant's erasure.
CREATE TABLE identity.mentor_decision
(
    id            UUID PRIMARY KEY,
    account_id    UUID        NOT NULL REFERENCES identity.account (id),
    decision      TEXT        NOT NULL CHECK (decision IN ('APPROVED', 'REJECTED')),
    reason        TEXT CHECK (char_length(reason) BETWEEN 1 AND 500),
    reapply_after TIMESTAMPTZ,
    decided_by    UUID        NOT NULL REFERENCES identity.account (id),
    decided_at    TIMESTAMPTZ NOT NULL,
    CHECK ((decision = 'REJECTED') = (reason IS NOT NULL)),
    CHECK ((decision = 'REJECTED') = (reapply_after IS NOT NULL))
);

CREATE INDEX mentor_decision_account_idx ON identity.mentor_decision (account_id, decided_at);

GRANT SELECT, INSERT, DELETE ON identity.mentor_decision TO ${runtime_role};

ALTER TABLE identity.mentor_application
    ADD COLUMN decision_id UUID REFERENCES identity.mentor_decision (id);
