-- FR-36, FR-43, AD-14, AD-15 (Story 14.2): a TOP_UP order names the Change Request it pays the difference
-- of; its FUND is booked in the CHANGE_REQUEST bucket with a TOPUP_APPLIED on the Milestone. A Held
-- Milestone's price cut is refunded at its final transition (PRICE_CUT).

ALTER TABLE payment.payment_order
    ADD COLUMN change_request_id UUID,
    ADD CONSTRAINT payment_order_top_up_check CHECK ((purpose = 'TOP_UP') = (change_request_id IS NOT NULL));

ALTER TABLE payment.refund DROP CONSTRAINT refund_reason_check;

ALTER TABLE payment.refund
    ADD CONSTRAINT refund_reason_check
        CHECK (reason IN ('CREW_CHANGED', 'ORDER_CLOSED', 'WRONG_AMOUNT', 'NOT_FUNDABLE', 'CANCELLED', 'DISPUTE_DECISION',
                          'PRICE_CUT'));
