-- AD-20, AD-40 (Story 12.3): the Administrator read grants an escalated Team Issue pushes down to job for
-- the Crew Terms versions (CREW_TERMS); without an open grant an Administrator reads none, and every read
-- a grant permits is audited (AD-8).
-- Personal data: deleted with the Administrator's erasure and with the Product (JobPersonalData).

CREATE TABLE job.admin_read_grant
(
    grant_id         UUID PRIMARY KEY,
    admin_account_id UUID  NOT NULL,
    job_id           UUID  NOT NULL,
    items            JSONB NOT NULL,
    reason_id        UUID  NOT NULL,
    ends_at          TIMESTAMPTZ
);

CREATE INDEX admin_read_grant_job_idx ON job.admin_read_grant (job_id, admin_account_id);

GRANT SELECT, INSERT, UPDATE, DELETE ON job.admin_read_grant TO ${runtime_role};
