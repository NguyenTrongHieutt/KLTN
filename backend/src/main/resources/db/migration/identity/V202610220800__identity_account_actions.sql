-- FR-59, FR-63, FR-74: what Administrators did to an account, each with its reason: a lock, an unlock,
-- the revocation of a Student's verification, or of a Mentor's approval with its ground (FR-78). ADM-2
-- shows the history; the Audit Log records the same. Personal data: exported, and deleted on the
-- account's erasure.

CREATE TABLE identity.account_action
(
    id         UUID PRIMARY KEY,
    account_id UUID        NOT NULL REFERENCES identity.account (id),
    action     TEXT        NOT NULL CHECK (action IN ('LOCKED', 'UNLOCKED', 'VERIFICATION_REVOKED', 'APPROVAL_REVOKED')),
    reason     TEXT        NOT NULL CHECK (char_length(reason) BETWEEN 1 AND 500),
    ground     TEXT CHECK (ground IN ('OTHER', 'PRIVATE_FEEDBACK')),
    acted_by   UUID        NOT NULL REFERENCES identity.account (id),
    acted_at   TIMESTAMPTZ NOT NULL,
    CHECK ((action = 'APPROVAL_REVOKED') = (ground IS NOT NULL))
);

CREATE INDEX account_action_account_idx ON identity.account_action (account_id, acted_at);

GRANT SELECT, INSERT, DELETE ON identity.account_action TO ${runtime_role};
