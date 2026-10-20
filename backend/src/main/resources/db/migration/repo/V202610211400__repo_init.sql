-- Schema repo (FR-66, AD-2, AD-19, AD-41): the platform repositories, access, and the Revocation
-- Checklist arrive with Epic 7; the first records are the post-Job data-deletion confirmations. After J8
-- or J10 the Mentor and every Crew member who was ever active confirm, item by item, that they deleted
-- the Customer's non-code data (credentials, database exports, customer files) and their preview
-- deployments. Each confirmation keeps the person and the time and is never changed.
-- Personal data: exported for the person, deleted on their erasure and with the Product
-- (RepoPersonalData).

CREATE SCHEMA repo;

GRANT USAGE ON SCHEMA repo TO ${runtime_role};

CREATE TABLE repo.data_deletion_confirmation
(
    job_id       UUID        NOT NULL,
    product_id   UUID        NOT NULL,
    account_id   UUID        NOT NULL,
    item         TEXT        NOT NULL CHECK (item IN ('NON_CODE_DATA', 'PREVIEWS')),
    confirmed_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (job_id, account_id, item)
);

CREATE INDEX data_deletion_account_idx ON repo.data_deletion_confirmation (account_id);

CREATE INDEX data_deletion_product_idx ON repo.data_deletion_confirmation (product_id);

GRANT SELECT, INSERT, DELETE ON repo.data_deletion_confirmation TO ${runtime_role};
