-- Person-link signals (AD-19, D1): HMACs of an account's verified email addresses and of its
-- LinkedIn profile, so one person holding two accounts can be told apart from two people, without
-- keeping the addresses here. One row per live key version, so a match survives a key rotation.
-- Append-only: rows are only purged, 24 months after the account's erasure (AD-24). Personal data:
-- exported as their kinds only, kept on erasure until that purge.
CREATE TABLE identity.person_signal
(
    id          UUID PRIMARY KEY,
    account_id  UUID        NOT NULL REFERENCES identity.account (id),
    kind        TEXT        NOT NULL CHECK (kind IN ('EMAIL', 'LINKEDIN')),
    source      TEXT        NOT NULL CHECK (source IN ('GITHUB_EMAIL', 'SCHOOL_EMAIL', 'LINKEDIN')),
    hmac        TEXT        NOT NULL CHECK (hmac ~ '^[0-9a-f]{64}$'),
    key_version INTEGER     NOT NULL CHECK (key_version > 0),
    recorded_at TIMESTAMPTZ NOT NULL,
    CHECK ((kind = 'LINKEDIN') = (source = 'LINKEDIN')),
    UNIQUE (account_id, kind, key_version, hmac)
);

CREATE INDEX person_signal_match_idx ON identity.person_signal (kind, key_version, hmac);

GRANT SELECT, INSERT, DELETE ON identity.person_signal TO ${runtime_role};
