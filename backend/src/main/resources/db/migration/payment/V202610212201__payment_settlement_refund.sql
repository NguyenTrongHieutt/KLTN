-- M8 (FR-54, AD-31): a decided settlement refunds its refund total with the reason DISPUTE_DECISION.

ALTER TABLE payment.refund DROP CONSTRAINT refund_reason_check;

ALTER TABLE payment.refund
    ADD CONSTRAINT refund_reason_check
        CHECK (reason IN ('CREW_CHANGED', 'ORDER_CLOSED', 'WRONG_AMOUNT', 'NOT_FUNDABLE', 'CANCELLED', 'DISPUTE_DECISION'));
