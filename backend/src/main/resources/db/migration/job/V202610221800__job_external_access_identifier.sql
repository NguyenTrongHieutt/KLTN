-- Story 8.1 (FR-66, AD-20, UX-DR83): the account the Mentor or a Crew member asks the Customer to invite on
-- one External Access item's system. Only the Customer reads it, next to that item, and only while the Job
-- is neither Closed nor Cancelled; each person reads their own. It is no credential: one line of at most
-- 200 characters, refused when the scanning rules read it as a secret.
-- Personal data: exported for its person and deleted on erasure; a Product's deletion deletes it
-- (JobPersonalData).

CREATE TABLE job.external_access_identifier
(
    access_id  UUID        NOT NULL REFERENCES job.external_access (id),
    account_id UUID        NOT NULL,
    job_id     UUID        NOT NULL REFERENCES job.job (id),
    identifier TEXT        NOT NULL CHECK (char_length(identifier) BETWEEN 1 AND 200),
    updated_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (access_id, account_id)
);

CREATE INDEX external_access_identifier_job_idx ON job.external_access_identifier (job_id);

CREATE INDEX external_access_identifier_account_idx ON job.external_access_identifier (account_id);

GRANT SELECT, INSERT, UPDATE, DELETE ON job.external_access_identifier TO ${runtime_role};
