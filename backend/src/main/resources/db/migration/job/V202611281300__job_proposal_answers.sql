-- FR-40, FR-36, AD-47 exceptions 3 and 6, R56 (Story 25.7): in a Self-mentored Job the Customer is the Mentor, so a
-- cancellation with a Held Milestone, and a Change Request that is not only a later due date, need the agreement of the
-- Crew members instead of "the other side". job.proposal_answer holds one row per member who counted when the Customer
-- made the proposal (the ACTIVE members then, counted as AD-41 counts), filled once by that member's own answer. A member
-- who leaves or is locked before answering no longer counts, which the code reads from the Crew rows at the time; one
-- who joins afterwards has no row and is not asked. The proposal's ID is the version an answer names: neither kind of
-- proposal is edited once made.
-- A cancellation a member refused ends REFUSED: the Customer or an active member may then open a Dispute.
-- Personal data: member_account_id names a person; an erasure clears it (JobPersonalData). The rows go with the Job's
-- data, as the proposals do.

CREATE TABLE job.proposal_answer
(
    id                UUID PRIMARY KEY,
    job_id            UUID        NOT NULL REFERENCES job.job (id),
    proposal_kind     TEXT        NOT NULL CHECK (proposal_kind IN ('CANCELLATION', 'CHANGE_REQUEST')),
    proposal_id       UUID        NOT NULL,
    member_account_id UUID,
    answer            TEXT CHECK (answer IN ('AGREED', 'REFUSED')),
    answered_at       TIMESTAMPTZ,
    created_at        TIMESTAMPTZ NOT NULL,
    CHECK ((answer IS NULL) = (answered_at IS NULL)),
    UNIQUE (proposal_id, member_account_id)
);

CREATE INDEX proposal_answer_job_idx ON job.proposal_answer (job_id);

CREATE INDEX proposal_answer_member_idx ON job.proposal_answer (member_account_id);

GRANT SELECT, INSERT, DELETE ON job.proposal_answer TO ${runtime_role};

GRANT UPDATE (answer, answered_at, member_account_id) ON job.proposal_answer TO ${runtime_role};

ALTER TABLE job.cancellation_proposal
    DROP CONSTRAINT cancellation_proposal_status_check,
    ADD CONSTRAINT cancellation_proposal_status_check CHECK (status IN ('OPEN', 'CONFIRMED', 'WITHDRAWN', 'REFUSED'));
