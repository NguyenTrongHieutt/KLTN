-- M7 (FR-40, FR-43): a cancelled Held Milestone is refunded in full, with the reason CANCELLED.

ALTER TABLE payment.refund DROP CONSTRAINT refund_reason_check;

ALTER TABLE payment.refund
    ADD CONSTRAINT refund_reason_check
        CHECK (reason IN ('CREW_CHANGED', 'ORDER_CLOSED', 'WRONG_AMOUNT', 'NOT_FUNDABLE', 'CANCELLED'));
