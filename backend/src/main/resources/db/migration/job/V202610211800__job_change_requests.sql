-- Due-date Change Requests (FR-36, AD-29): the Customer or the Mentor proposes new due dates for
-- Milestones; the Mentor states the time impact; the Customer approves and the Mentor confirms on the
-- same Job version; the confirmation writes the next immutable confirmed Agreement version. An open one
-- lapses when a touched Milestone is accepted or settled, or the Job enters Closing or Cancelled.
-- Personal data: the proposer and the deciders are cleared on erasure; a Product's deletion deletes its
-- Jobs' requests with them.

CREATE TABLE job.change_request
(
    id                   UUID PRIMARY KEY,
    job_id               UUID        NOT NULL REFERENCES job.job (id),
    proposer             TEXT        NOT NULL CHECK (proposer IN ('CUSTOMER', 'MENTOR')),
    proposer_account_id  UUID,
    description          TEXT CHECK (char_length(description) BETWEEN 1 AND 2000),
    impact               TEXT CHECK (char_length(impact) BETWEEN 1 AND 1000),
    items                JSONB       NOT NULL,
    status               TEXT        NOT NULL CHECK (status IN ('OPEN', 'APPLIED', 'LAPSED', 'WITHDRAWN')),
    customer_approved_at TIMESTAMPTZ,
    customer_account_id  UUID,
    mentor_confirmed_at  TIMESTAMPTZ,
    mentor_account_id    UUID,
    agreement_version_no INTEGER CHECK (agreement_version_no > 1),
    created_at           TIMESTAMPTZ NOT NULL,
    closed_at            TIMESTAMPTZ,
    CHECK ((status = 'OPEN') = (closed_at IS NULL)),
    CHECK ((status = 'APPLIED') = (agreement_version_no IS NOT NULL)),
    CHECK (status <> 'APPLIED' OR (customer_approved_at IS NOT NULL AND mentor_confirmed_at IS NOT NULL))
);

CREATE UNIQUE INDEX change_request_open_idx ON job.change_request (job_id) WHERE status = 'OPEN';

CREATE INDEX change_request_job_idx ON job.change_request (job_id, created_at);

GRANT SELECT, INSERT, DELETE ON job.change_request TO ${runtime_role};

GRANT UPDATE (impact, status, customer_approved_at, customer_account_id, mentor_confirmed_at, mentor_account_id,
    agreement_version_no, closed_at, proposer_account_id) ON job.change_request TO ${runtime_role};
