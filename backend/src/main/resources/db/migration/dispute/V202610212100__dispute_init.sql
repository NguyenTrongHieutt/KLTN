-- Schema dispute (FR-52, AD-31): a Dispute the Customer or the Mentor opens on a running Job, which
-- freezes the Job's money and pausable Deadlines until an Administrator decides (Story 9.5). It concerns
-- the Held Milestone, or the Job's next state when none is held; released funds are never disputed.
-- Personal data: the opener is cleared on erasure; a Product's deletion deletes its Disputes.

CREATE SCHEMA dispute;

GRANT USAGE ON SCHEMA dispute TO ${runtime_role};

CREATE TABLE dispute.dispute
(
    id                UUID PRIMARY KEY,
    job_id            UUID        NOT NULL,
    product_id        UUID        NOT NULL,
    opened_by         TEXT        NOT NULL CHECK (opened_by IN ('CUSTOMER', 'MENTOR')),
    opener_account_id UUID,
    reason            TEXT        NOT NULL CHECK (char_length(reason) BETWEEN 1 AND 2000),
    concern           TEXT        NOT NULL CHECK (concern IN ('HELD_MILESTONE', 'NEXT_STATE')),
    milestone_id      UUID,
    job_state         TEXT        NOT NULL,
    status            TEXT        NOT NULL CHECK (status IN ('OPEN', 'DECIDED')),
    opened_at         TIMESTAMPTZ NOT NULL,
    decided_at        TIMESTAMPTZ,
    CHECK ((concern = 'HELD_MILESTONE') = (milestone_id IS NOT NULL)),
    CHECK ((status = 'OPEN') = (decided_at IS NULL))
);

CREATE UNIQUE INDEX dispute_open_idx ON dispute.dispute (job_id) WHERE status = 'OPEN';

CREATE INDEX dispute_product_idx ON dispute.dispute (product_id);

GRANT SELECT, INSERT, DELETE ON dispute.dispute TO ${runtime_role};

GRANT UPDATE (status, decided_at, opener_account_id) ON dispute.dispute TO ${runtime_role};
