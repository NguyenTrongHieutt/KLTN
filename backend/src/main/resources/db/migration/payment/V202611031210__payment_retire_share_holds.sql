-- Story 22.4 (FR-42, AD-15, AD-40, SG-63): money moves only through the Milestone Split's pool, so the share holds of
-- the retired share Team Issues go, with the two ledger columns that named a hold and its transfers and the index that
-- kept one HELD_RELEASED per held entry. The entry types RELEASE_HELD and HELD_RELEASED leave the check, which is
-- validated: the reset dev database holds none, and a database that still does stops at boot until it is reset. No
-- ledger row is touched.
-- Personal data: the holds' raisers go with the table; nothing new is kept.

DROP TABLE payment.share_hold;

DROP INDEX payment.ledger_entry_reversal_idx;

ALTER TABLE payment.ledger_entry
    DROP COLUMN hold_id,
    DROP COLUMN transfer_ids,
    DROP CONSTRAINT ledger_entry_entry_type_check;

ALTER TABLE payment.ledger_entry
    ADD CONSTRAINT ledger_entry_entry_type_check
        CHECK (entry_type IN ('FUND', 'TOPUP_APPLIED', 'RELEASE_POOLED', 'POOL_RELEASED', 'RELEASE', 'REFUND_REQUESTED',
                              'REFUND_CONFIRMED'));
