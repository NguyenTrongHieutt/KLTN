-- FR-43, AD-6, AD-15: refunds and the ledger columns releases will use. A refund reverses one FUND
-- (one per vnp_TransactionNo), never more than its remaining amount; it is irrevocable, and its
-- vnp_RequestId and gateway are fixed when it is requested. Until spike S2 confirms sandbox refunds,
-- the gateway is LEDGER: the refund is recorded on vibefix only, as simulated test-mode money. A
-- HELD_RELEASED reverses at most one held entry (Epic 12 writes them).
-- Personal data: Job records; deleted with the Customer's Jobs and with the Product (PaymentPersonalData).

ALTER TABLE payment.ledger_entry
    ADD COLUMN party_account_id      UUID,
    ADD COLUMN crew_terms_version_no INTEGER CHECK (crew_terms_version_no > 0),
    ADD COLUMN share_percent         INTEGER CHECK (share_percent BETWEEN 0 AND 100),
    ADD COLUMN base_amount_vnd       BIGINT CHECK (base_amount_vnd > 0),
    ADD COLUMN hold_id               UUID,
    ADD COLUMN reverses_entry_id     UUID REFERENCES payment.ledger_entry (id),
    ADD COLUMN refund_id             UUID;

CREATE UNIQUE INDEX ledger_entry_reversal_idx ON payment.ledger_entry (reverses_entry_id)
    WHERE entry_type = 'HELD_RELEASED';

CREATE TABLE payment.refund
(
    id                  UUID PRIMARY KEY,
    fund_entry_id       UUID        NOT NULL UNIQUE REFERENCES payment.ledger_entry (id),
    payment_order_id    UUID        NOT NULL REFERENCES payment.payment_order (id),
    job_id              UUID        NOT NULL,
    product_id          UUID        NOT NULL,
    milestone_id        UUID        NOT NULL,
    customer_account_id UUID        NOT NULL,
    amount_vnd          BIGINT      NOT NULL CHECK (amount_vnd > 0),
    reason              TEXT        NOT NULL CHECK (reason IN ('CREW_CHANGED', 'ORDER_CLOSED', 'WRONG_AMOUNT', 'NOT_FUNDABLE')),
    gateway             TEXT        NOT NULL CHECK (gateway IN ('LEDGER', 'VNPAY')),
    vnp_request_id      TEXT        NOT NULL UNIQUE,
    status              TEXT        NOT NULL CHECK (status IN ('REQUESTED', 'CONFIRMED')),
    requested_at        TIMESTAMPTZ NOT NULL,
    confirmed_at        TIMESTAMPTZ,
    CHECK ((status = 'CONFIRMED') = (confirmed_at IS NOT NULL))
);

CREATE INDEX refund_order_idx ON payment.refund (payment_order_id);

CREATE INDEX refund_job_idx ON payment.refund (job_id);

GRANT SELECT, INSERT, UPDATE, DELETE ON payment.refund TO ${runtime_role};
