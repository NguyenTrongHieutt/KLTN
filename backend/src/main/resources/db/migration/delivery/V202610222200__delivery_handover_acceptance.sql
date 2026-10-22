-- Story 8.5 (FR-33, FR-35, AD-5, AD-28): a Code Job's acceptance waits for the merge of exactly the
-- Handover Commit. Its row keeps the Customer's marks (or AUTO), the precondition operation's key, and
-- where it stands: MERGING while the busy marker is set, then DONE (merged, M5 and RELEASE applied),
-- VOIDED (the head had moved: M3), or FAILED (every attempt failed; the Customer may try again).
-- Personal data: the Customer's marks and comments; a Product's deletion deletes the rows
-- (DeliveryPersonalData); erasure clears the Customer.

CREATE TABLE delivery.handover_acceptance
(
    operation_key       TEXT PRIMARY KEY,
    seq                 BIGINT      NOT NULL GENERATED ALWAYS AS IDENTITY,
    handover_id         UUID        NOT NULL REFERENCES delivery.handover (id),
    job_id              UUID        NOT NULL,
    product_id          UUID        NOT NULL,
    customer_account_id UUID,
    auto                BOOLEAN     NOT NULL,
    checks              JSONB       NOT NULL DEFAULT '[]',
    status              TEXT        NOT NULL CHECK (status IN ('MERGING', 'DONE', 'VOIDED', 'FAILED')),
    requested_at        TIMESTAMPTZ NOT NULL,
    resolved_at         TIMESTAMPTZ,
    CHECK ((status = 'MERGING') = (resolved_at IS NULL))
);

CREATE UNIQUE INDEX handover_acceptance_merging_idx ON delivery.handover_acceptance (handover_id) WHERE status = 'MERGING';

CREATE INDEX handover_acceptance_job_idx ON delivery.handover_acceptance (job_id);

CREATE INDEX handover_acceptance_customer_idx ON delivery.handover_acceptance (customer_account_id)
    WHERE customer_account_id IS NOT NULL;

GRANT SELECT, INSERT, UPDATE, DELETE ON delivery.handover_acceptance TO ${runtime_role};
