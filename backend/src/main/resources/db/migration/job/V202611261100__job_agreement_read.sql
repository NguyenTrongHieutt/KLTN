-- FR-75, FR-81, AD-41 (Story 25.1b): the newest Agreement version a picked applicant or a Crew member opened on JOB-6,
-- one row per Job and reader. A shared draft is (confirmed = false, its draft number); a confirmed version is
-- (confirmed = true, its version number), and any confirmed version is newer than any draft. job reads it to tell the
-- reader once when a newer version becomes readable to them (job.agreement_draft_changed) and to say so on the tab until
-- they open it. A row only moves forward.
-- Personal data: the reader's account ID with what they read and when. Exported for its person, deleted on the
-- account's erasure, with the Job when its Product is deleted, and by the retention run (JobPersonalData).

CREATE TABLE job.agreement_read
(
    job_id     UUID        NOT NULL REFERENCES job.job (id),
    account_id UUID        NOT NULL,
    confirmed  BOOLEAN     NOT NULL,
    version_no INTEGER     NOT NULL CHECK (version_no > 0),
    read_at    TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (job_id, account_id)
);

CREATE INDEX agreement_read_account_idx ON job.agreement_read (account_id);

GRANT SELECT, INSERT, UPDATE, DELETE ON job.agreement_read TO ${runtime_role};
