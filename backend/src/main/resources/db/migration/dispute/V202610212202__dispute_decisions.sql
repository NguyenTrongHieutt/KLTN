-- Decisions and their execution (FR-54, AD-31): an Administrator takes a Dispute and records a reasoned
-- decision on the Held Milestone's money and the Job's next state; the execution runs its four steps in
-- order, persisted, and only its last one unfreezes the Job. Decisions are final.
-- Personal data: the deciding Administrator stays (an Administrator's work is not their personal data
-- here; their erasure clears them); a Product's deletion deletes its Disputes' decisions.

ALTER TABLE dispute.dispute
    ADD COLUMN assigned_admin_id UUID,
    ADD COLUMN assigned_at       TIMESTAMPTZ,
    ADD CONSTRAINT dispute_assigned_check CHECK ((assigned_admin_id IS NULL) = (assigned_at IS NULL));

GRANT UPDATE (assigned_admin_id, assigned_at) ON dispute.dispute TO ${runtime_role};

CREATE TABLE dispute.decision
(
    dispute_id       UUID PRIMARY KEY REFERENCES dispute.dispute (id),
    admin_account_id UUID,
    milestone_id     UUID,
    releasable_vnd   BIGINT CHECK (releasable_vnd >= 0),
    release_vnd      BIGINT CHECK (release_vnd >= 0),
    refund_vnd       BIGINT CHECK (refund_vnd >= 0),
    merged           BOOLEAN     NOT NULL DEFAULT false,
    next_state       TEXT        NOT NULL CHECK (next_state IN ('CONTINUE', 'END', 'CANCEL')),
    reasoning        TEXT        NOT NULL CHECK (char_length(reasoning) BETWEEN 1 AND 4000),
    decided_at       TIMESTAMPTZ NOT NULL,
    CHECK ((milestone_id IS NULL) = (releasable_vnd IS NULL)),
    CHECK ((milestone_id IS NULL) = (release_vnd IS NULL AND refund_vnd IS NULL)),
    CHECK (milestone_id IS NULL OR release_vnd + refund_vnd = releasable_vnd)
);

GRANT SELECT, INSERT, DELETE ON dispute.decision TO ${runtime_role};

GRANT UPDATE (admin_account_id) ON dispute.decision TO ${runtime_role};

CREATE TABLE dispute.execution
(
    dispute_id UUID PRIMARY KEY REFERENCES dispute.dispute (id),
    status     TEXT        NOT NULL CHECK (status IN ('RUNNING', 'DONE', 'EXECUTION_FAILED')),
    step       INTEGER     NOT NULL CHECK (step BETWEEN 0 AND 4),
    failure    TEXT,
    updated_at TIMESTAMPTZ NOT NULL,
    CHECK ((status = 'DONE') = (step = 4)),
    CHECK ((status = 'EXECUTION_FAILED') = (failure IS NOT NULL))
);

GRANT SELECT, INSERT, DELETE ON dispute.execution TO ${runtime_role};

GRANT UPDATE (status, step, failure, updated_at) ON dispute.execution TO ${runtime_role};
