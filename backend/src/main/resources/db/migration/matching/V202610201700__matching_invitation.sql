-- FR-14: a Customer invites an approved Mentor to apply to their Open Job Post, once per Mentor and
-- Job. An invitation is OPEN until the Mentor applies (APPLIED) or the Job leaves Open (LAPSED, J2 or
-- J3); it reserves nothing and selects no one. The daily counters (10 per Job Post, 30 per Customer,
-- AD-34) count rows by invited_at. Only the invited Mentor and the Customer read one (AD-20).
-- Personal data: exported for both, deleted on either's erasure and with the Product (MatchingPersonalData).

CREATE TABLE matching.invitation
(
    id                  UUID PRIMARY KEY,
    job_id              UUID        NOT NULL,
    product_id          UUID        NOT NULL,
    customer_account_id UUID        NOT NULL,
    mentor_account_id   UUID        NOT NULL,
    status              TEXT        NOT NULL CHECK (status IN ('OPEN', 'APPLIED', 'LAPSED')),
    invited_at          TIMESTAMPTZ NOT NULL,
    ended_at            TIMESTAMPTZ,
    UNIQUE (job_id, mentor_account_id),
    CHECK ((status = 'OPEN') = (ended_at IS NULL))
);

CREATE INDEX invitation_customer_idx ON matching.invitation (customer_account_id, invited_at);

CREATE INDEX invitation_job_idx ON matching.invitation (job_id, invited_at);

CREATE INDEX invitation_mentor_idx ON matching.invitation (mentor_account_id);

CREATE INDEX invitation_product_idx ON matching.invitation (product_id);

GRANT SELECT, INSERT, UPDATE, DELETE ON matching.invitation TO ${runtime_role};
