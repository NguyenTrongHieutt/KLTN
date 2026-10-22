-- FR-52, AD-7 (Story 15.1): the other side's statement period, copied from the Job's DISPUTE_RESPONSE_DAYS at
-- the opening, and each side's one statement. A decision waits until a statement arrived or the period
-- passed. A Dispute opened before this story has its period counted from its opening.
-- Personal data: the statement's author is exported, cleared on erasure, and deleted with the Product
-- (DisputePersonalData).

ALTER TABLE dispute.dispute
    ADD COLUMN response_days    INTEGER NOT NULL DEFAULT 5 CHECK (response_days BETWEEN 1 AND 30),
    ADD COLUMN statement_due_at TIMESTAMPTZ;

UPDATE dispute.dispute SET statement_due_at = opened_at + make_interval(days => response_days);

CREATE TABLE dispute.statement
(
    dispute_id   UUID        NOT NULL REFERENCES dispute.dispute (id),
    side         TEXT        NOT NULL CHECK (side IN ('CUSTOMER', 'MENTOR')),
    account_id   UUID,
    text         TEXT        NOT NULL CHECK (char_length(text) BETWEEN 1 AND 4000),
    submitted_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (dispute_id, side)
);

GRANT SELECT, INSERT, DELETE ON dispute.statement TO ${runtime_role};

GRANT UPDATE (account_id) ON dispute.statement TO ${runtime_role};
