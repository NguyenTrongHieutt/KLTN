-- Cancellation by agreement (FR-40): either side proposes cancelling the Job or its remaining
-- Milestones with a reason; the other side confirms within the Job's copied days, and the cascade cancels
-- the Planned Milestones (M6), refunds the Held one (M7), and ends the Job (J8 and J9, or J10).
-- Personal data: the proposer and the confirmer are cleared on erasure; a Product's deletion deletes
-- its Jobs' proposals with them.

ALTER TABLE job.job
    ADD COLUMN cancelled_at TIMESTAMPTZ;

CREATE TABLE job.cancellation_proposal
(
    id                  UUID PRIMARY KEY,
    job_id              UUID        NOT NULL REFERENCES job.job (id),
    scope               TEXT        NOT NULL CHECK (scope IN ('JOB', 'REMAINING')),
    reason              TEXT        NOT NULL CHECK (char_length(reason) BETWEEN 1 AND 2000),
    proposer            TEXT        NOT NULL CHECK (proposer IN ('CUSTOMER', 'MENTOR')),
    proposer_account_id UUID,
    status              TEXT        NOT NULL CHECK (status IN ('OPEN', 'CONFIRMED', 'WITHDRAWN')),
    answer_by           TIMESTAMPTZ NOT NULL,
    unanswered_at       TIMESTAMPTZ,
    confirmed_by        UUID,
    created_at          TIMESTAMPTZ NOT NULL,
    decided_at          TIMESTAMPTZ,
    CHECK ((status = 'OPEN') = (decided_at IS NULL))
);

CREATE UNIQUE INDEX cancellation_open_idx ON job.cancellation_proposal (job_id) WHERE status = 'OPEN';

CREATE INDEX cancellation_job_idx ON job.cancellation_proposal (job_id, created_at);

GRANT SELECT, INSERT, DELETE ON job.cancellation_proposal TO ${runtime_role};

GRANT UPDATE (status, unanswered_at, confirmed_by, decided_at, proposer_account_id)
    ON job.cancellation_proposal TO ${runtime_role};
