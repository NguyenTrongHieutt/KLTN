-- FR-42, FR-79, AD-15, AD-40: each share Team Issue's hold on a Milestone, one per Team Issue. While a
-- row on the Milestone is open, its release writes RELEASE_HELD for the Mentor and for each open row's
-- raiser instead of RELEASE; the transfer the issue's ending records (whole percentage points from the
-- Mentor's share in the pin to the raiser's, 0 for none) stays on its row and adjusts every later split.
-- Entries written with transfers name the transfers applied.
-- Personal data: Job records; deleted with the Job's ledger (PaymentPersonalData).

CREATE TABLE payment.share_hold
(
    team_issue_id     UUID PRIMARY KEY,
    milestone_id      UUID        NOT NULL REFERENCES payment.ledger_account (milestone_id),
    job_id            UUID        NOT NULL,
    product_id        UUID        NOT NULL,
    raiser_account_id UUID        NOT NULL,
    held_at           TIMESTAMPTZ NOT NULL,
    ended_at          TIMESTAMPTZ,
    transfer_points   INTEGER CHECK (transfer_points BETWEEN 0 AND 100),
    CHECK ((ended_at IS NULL) = (transfer_points IS NULL))
);

CREATE UNIQUE INDEX share_hold_raiser_idx ON payment.share_hold (milestone_id, raiser_account_id);

CREATE INDEX share_hold_job_idx ON payment.share_hold (job_id);

GRANT SELECT, INSERT, DELETE ON payment.share_hold TO ${runtime_role};

GRANT UPDATE (ended_at, transfer_points) ON payment.share_hold TO ${runtime_role};

ALTER TABLE payment.ledger_entry
    ADD COLUMN transfer_ids UUID[];
