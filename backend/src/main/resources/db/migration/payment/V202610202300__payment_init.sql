-- Schema payment (FR-41, AD-14, AD-15): test-mode money through the VNPay sandbox. A Payment Order
-- funds one Milestone's exact price at one Agreement version; a Job has at most one open order, which
-- expires after 15 minutes. Only the IPN changes an order's state. Money is an append-only ledger:
-- every entry has a unique operation key, and a FUND is keyed by the gateway's vnp_TransactionNo, so a
-- replayed IPN credits nothing twice. Story 4.6 writes FUND only; the other entry types arrive with
-- refunds (Story 4.7) and releases (Epic 6). Each Milestone has a ledger account row used for locking.
-- Personal data: a Customer's orders and the ledger of their Jobs are exported for them; they are
-- deleted with the Customer's Jobs and with the Product (PaymentPersonalData), test-mode money only.

CREATE SCHEMA payment;

GRANT USAGE ON SCHEMA payment TO ${runtime_role};

CREATE TABLE payment.payment_order
(
    id                    UUID PRIMARY KEY,
    job_id                UUID        NOT NULL,
    product_id            UUID        NOT NULL,
    milestone_id          UUID        NOT NULL,
    customer_account_id   UUID        NOT NULL,
    purpose               TEXT        NOT NULL CHECK (purpose IN ('FUNDING', 'TOP_UP')),
    amount_vnd            BIGINT      NOT NULL CHECK (amount_vnd > 0),
    agreement_version_no  INTEGER     NOT NULL CHECK (agreement_version_no > 0),
    txn_ref               TEXT        NOT NULL UNIQUE CHECK (txn_ref ~ '^[A-Za-z0-9]{1,100}$'),
    status                TEXT        NOT NULL CHECK (status IN ('OPEN', 'PAID', 'UNAPPLIED', 'FAILED', 'EXPIRED', 'VOIDED')),
    created_at            TIMESTAMPTZ NOT NULL,
    expires_at            TIMESTAMPTZ NOT NULL,
    finished_at           TIMESTAMPTZ,
    response_code         TEXT,
    transaction_status    TEXT,
    vnp_transaction_no    TEXT,
    CHECK ((status = 'OPEN') = (finished_at IS NULL))
);

CREATE UNIQUE INDEX payment_order_open_idx ON payment.payment_order (job_id) WHERE status = 'OPEN';

CREATE INDEX payment_order_job_idx ON payment.payment_order (job_id, created_at);

CREATE INDEX payment_order_customer_idx ON payment.payment_order (customer_account_id);

GRANT SELECT, INSERT, UPDATE, DELETE ON payment.payment_order TO ${runtime_role};

CREATE TABLE payment.ledger_account
(
    milestone_id UUID PRIMARY KEY,
    job_id       UUID        NOT NULL,
    product_id   UUID        NOT NULL,
    created_at   TIMESTAMPTZ NOT NULL
);

CREATE INDEX ledger_account_job_idx ON payment.ledger_account (job_id);

-- UPDATE only so that SELECT ... FOR UPDATE can lock the row (AD-15); no statement updates it.
GRANT SELECT, INSERT, UPDATE, DELETE ON payment.ledger_account TO ${runtime_role};

CREATE TABLE payment.ledger_entry
(
    id                 UUID PRIMARY KEY,
    operation_key      TEXT        NOT NULL UNIQUE,
    entry_type         TEXT        NOT NULL CHECK (entry_type IN ('FUND', 'TOPUP_APPLIED', 'RELEASE', 'RELEASE_HELD',
        'HELD_RELEASED', 'REFUND_REQUESTED', 'REFUND_CONFIRMED')),
    bucket             TEXT CHECK (bucket IN ('MILESTONE', 'CHANGE_REQUEST', 'UNAPPLIED')),
    job_id             UUID        NOT NULL,
    product_id         UUID        NOT NULL,
    milestone_id       UUID,
    payment_order_id   UUID REFERENCES payment.payment_order (id),
    amount_vnd         BIGINT      NOT NULL CHECK (amount_vnd > 0),
    vnp_transaction_no TEXT,
    created_at         TIMESTAMPTZ NOT NULL,
    CHECK (entry_type <> 'FUND' OR (bucket IS NOT NULL AND vnp_transaction_no IS NOT NULL)),
    CHECK (bucket IS DISTINCT FROM 'MILESTONE' OR milestone_id IS NOT NULL)
);

CREATE UNIQUE INDEX ledger_entry_fund_idx ON payment.ledger_entry (vnp_transaction_no) WHERE entry_type = 'FUND';

CREATE INDEX ledger_entry_milestone_idx ON payment.ledger_entry (milestone_id);

CREATE INDEX ledger_entry_job_idx ON payment.ledger_entry (job_id);

GRANT SELECT, INSERT, DELETE ON payment.ledger_entry TO ${runtime_role};
