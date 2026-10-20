-- FR-61, FR-63: each change an Administrator saves on ADM-10, with its old and new value: a rule's
-- value, or a school domain added or removed. Running Jobs keep the values they copied when published.
-- Personal data: the Administrator who saved it; kept as the record, the account is anonymized.

CREATE TABLE settings.change
(
    id         UUID PRIMARY KEY,
    kind       TEXT        NOT NULL CHECK (kind IN ('RULE', 'DOMAIN_ADDED', 'DOMAIN_REMOVED')),
    rule_key   TEXT,
    old_value  INTEGER,
    new_value  INTEGER,
    domain     TEXT,
    changed_by UUID,
    changed_at TIMESTAMPTZ NOT NULL,
    CHECK ((kind = 'RULE') = (rule_key IS NOT NULL AND old_value IS NOT NULL AND new_value IS NOT NULL)),
    CHECK ((kind = 'RULE') = (domain IS NULL))
);

CREATE INDEX change_time_idx ON settings.change (changed_at DESC);

GRANT SELECT, INSERT ON settings.change TO ${runtime_role};

GRANT UPDATE (changed_by) ON settings.change TO ${runtime_role};
