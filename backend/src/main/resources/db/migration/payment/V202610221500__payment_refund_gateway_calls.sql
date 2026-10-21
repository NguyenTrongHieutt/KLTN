-- AD-15, Spike S2 (2026-10-21): refunds go through VnpayRefund. Each gateway call is counted, so the
-- Customer hears of a confirmation only when the first call did not confirm the refund (EXPERIENCE.md,
-- one notice per refund). REFUND_CONFIRMED keeps the refund's own vnp_TransactionNo in the ledger entry.

ALTER TABLE payment.refund
    ADD COLUMN gateway_calls INTEGER NOT NULL DEFAULT 0 CHECK (gateway_calls >= 0);
