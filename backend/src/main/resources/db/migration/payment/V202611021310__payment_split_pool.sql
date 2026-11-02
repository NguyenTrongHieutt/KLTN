-- FR-42, AD-15, AD-24 (Story 22.1): a release holds the whole amount in one RELEASE_POOLED with no party, at most one
-- per Milestone; the final split reverses it whole with one POOL_RELEASED, at most one per pool, beside the RELEASE
-- entries it pays. A RELEASE records the split version it follows, so its column stops naming Crew Terms.
-- Personal data: the ledger, the Payment Orders and the refunds are financial records under NFR-10's legal retention.
-- A Product deletion and an erasure keep them, and an erasure sets the Customer's account on orders and refunds to NULL
-- (PaymentPersonalData).

ALTER TABLE payment.ledger_entry
    DROP CONSTRAINT ledger_entry_entry_type_check;

ALTER TABLE payment.ledger_entry
    ADD CONSTRAINT ledger_entry_entry_type_check
        CHECK (entry_type IN ('FUND', 'TOPUP_APPLIED', 'RELEASE_POOLED', 'POOL_RELEASED', 'RELEASE', 'RELEASE_HELD',
                              'HELD_RELEASED', 'REFUND_REQUESTED', 'REFUND_CONFIRMED')),
    ADD CONSTRAINT ledger_entry_pool_no_party_check
        CHECK (entry_type NOT IN ('RELEASE_POOLED', 'POOL_RELEASED') OR party_account_id IS NULL),
    ADD CONSTRAINT ledger_entry_pool_reversal_check
        CHECK (entry_type <> 'POOL_RELEASED' OR reverses_entry_id IS NOT NULL);

CREATE UNIQUE INDEX ledger_entry_pool_idx ON payment.ledger_entry (milestone_id)
    WHERE entry_type = 'RELEASE_POOLED';

CREATE UNIQUE INDEX ledger_entry_pool_reversal_idx ON payment.ledger_entry (reverses_entry_id)
    WHERE entry_type = 'POOL_RELEASED';

ALTER TABLE payment.ledger_entry
    RENAME COLUMN crew_terms_version_no TO split_version_no;

ALTER TABLE payment.ledger_entry
    RENAME CONSTRAINT ledger_entry_crew_terms_version_no_check TO ledger_entry_split_version_no_check;

ALTER TABLE payment.payment_order
    ALTER COLUMN customer_account_id DROP NOT NULL;

GRANT UPDATE (customer_account_id) ON payment.payment_order TO ${runtime_role};

ALTER TABLE payment.refund
    ALTER COLUMN customer_account_id DROP NOT NULL;

GRANT UPDATE (customer_account_id) ON payment.refund TO ${runtime_role};
