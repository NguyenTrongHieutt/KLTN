-- NFR-11, AD-24 (Story 16.2): a person's request to delete their account, one per account, waiting until no
-- participant reports a blocker and an Administrator confirms it on ADM-2 behind the step-up. The row stays
-- after the erasure as the record that the request was carried out.
-- Personal data: none beyond the account's own ID; the confirming Administrator is cleared on their erasure.

CREATE TABLE identity.deletion_request
(
    id                    UUID PRIMARY KEY,
    account_id            UUID        NOT NULL UNIQUE REFERENCES identity.account (id),
    requested_at          TIMESTAMPTZ NOT NULL,
    completed_at          TIMESTAMPTZ,
    completed_by_admin_id UUID
);

GRANT SELECT, INSERT ON identity.deletion_request TO ${runtime_role};

GRANT UPDATE (completed_at, completed_by_admin_id) ON identity.deletion_request TO ${runtime_role};
