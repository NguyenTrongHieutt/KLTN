-- FR-37, FR-67, AD-4, AD-41: closing a Job. A closure proposal is AUTOMATIC (the System, after the last
-- acceptance, confirmed by a Deadline unless a side declines) or PROPOSED (by the Customer or the
-- Mentor, confirmed by the other side); at most one is open per Job, and each side's confirmation, a
-- decline, and the decision are kept. J8 records when the Job started closing, M6 when a Planned
-- Milestone was cancelled, and J9 when the Job closed.
-- Personal data: deleted with the Customer's Jobs and the Product; the proposer and decliner are
-- cleared on erasure (JobPersonalData).

ALTER TABLE job.job
    ADD COLUMN closing_at TIMESTAMPTZ,
    ADD COLUMN closed_at  TIMESTAMPTZ;

ALTER TABLE job.milestone
    ADD COLUMN cancelled_at TIMESTAMPTZ;

CREATE TABLE job.closure_proposal
(
    id                    UUID PRIMARY KEY,
    job_id                UUID        NOT NULL REFERENCES job.job (id),
    kind                  TEXT        NOT NULL CHECK (kind IN ('AUTOMATIC', 'PROPOSED')),
    proposer_account_id   UUID,
    status                TEXT        NOT NULL CHECK (status IN ('OPEN', 'CONFIRMED', 'DECLINED')),
    customer_confirmed_at TIMESTAMPTZ,
    mentor_confirmed_at   TIMESTAMPTZ,
    declined_by           UUID,
    decided_at            TIMESTAMPTZ,
    auto_confirm_at       TIMESTAMPTZ,
    answer_by             TIMESTAMPTZ NOT NULL,
    created_at            TIMESTAMPTZ NOT NULL,
    CHECK ((kind = 'AUTOMATIC') = (auto_confirm_at IS NOT NULL)),
    CHECK ((status = 'OPEN') = (decided_at IS NULL)),
    CHECK (declined_by IS NULL OR status = 'DECLINED')
);

CREATE UNIQUE INDEX closure_proposal_open_idx ON job.closure_proposal (job_id) WHERE status = 'OPEN';

CREATE INDEX closure_proposal_job_idx ON job.closure_proposal (job_id, created_at);

GRANT SELECT, INSERT, UPDATE, DELETE ON job.closure_proposal TO ${runtime_role};
