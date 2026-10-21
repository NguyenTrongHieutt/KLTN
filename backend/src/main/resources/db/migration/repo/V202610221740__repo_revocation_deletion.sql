-- Story 7.7 (FR-21, FR-38, FR-59, AD-11, NFR-10): a grant ends as REVOKING until GitHub reads back no access,
-- then REVOKED with the time, which the Audit Log also records per person and repository. A Setup's
-- repositories are deleted 30 days after the Job is Closed or Cancelled, once the final ZIP of the
-- Intermediary Repository is stored, or at once when the Product is deleted.

ALTER TABLE repo.job_grant DROP CONSTRAINT job_grant_status_check;

ALTER TABLE repo.job_grant
    ADD CONSTRAINT job_grant_status_check CHECK (status IN ('INVITING', 'INVITED', 'ACCEPTED', 'MEMBER_DRIFT', 'WRITE',
        'REVOKING', 'REVOKED')),
    ADD COLUMN revoked_at TIMESTAMPTZ,
    ADD CONSTRAINT job_grant_revoked_check CHECK ((status = 'REVOKED') = (revoked_at IS NOT NULL));

ALTER TABLE repo.job_grant DROP CONSTRAINT job_grant_check;

ALTER TABLE repo.job_grant
    ADD CONSTRAINT job_grant_write_check CHECK (status IN ('REVOKING', 'REVOKED') OR (status = 'WRITE') = (write_at IS NOT NULL));

ALTER TABLE repo.job_setup
    ADD COLUMN archive_file_id UUID,
    ADD COLUMN deleted_at      TIMESTAMPTZ;
