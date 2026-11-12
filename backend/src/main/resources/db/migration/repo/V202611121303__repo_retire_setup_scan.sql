-- Story 23.1 (D8, R16, FR-18): J7's Setup no longer waits for a scan or counts open Findings. Until Story 23.6 it
-- copies the Source Repository's default-branch head, read through the GitHub API. A Setup blocked on an open
-- Intake Finding is no longer blocked: it becomes FAILED, like an attempt whose operation ran out of retries, and
-- its operation (moved back to FAILED by operations' migration of this story) re-checks the Source Repository on
-- its next attempt, an Administrator's retry (FR-64). Its step 1 never finished, so the check runs again.
-- repo.push_scan stays until Story 23.2 retires the head tracking.

UPDATE repo.job_setup
SET status = 'FAILED', blocked_reason = NULL
WHERE blocked_reason = 'INTAKE_FINDING_OPEN';

-- The CHECKs that name open_findings or the retired reason go with it, by their definition, whatever their names.
DO $$
DECLARE
    found RECORD;
BEGIN
    FOR found IN SELECT conname FROM pg_constraint
                 WHERE conrelid = 'repo.job_setup'::regclass AND contype = 'c'
                   AND (pg_get_constraintdef(oid) LIKE '%open_findings%'
                        OR pg_get_constraintdef(oid) LIKE '%INTAKE_FINDING_OPEN%')
    LOOP
        EXECUTE format('ALTER TABLE repo.job_setup DROP CONSTRAINT %I', found.conname);
    END LOOP;
END $$;

ALTER TABLE repo.job_setup
    DROP COLUMN scan_run_id,
    DROP COLUMN open_findings;

ALTER TABLE repo.job_setup
    ADD CONSTRAINT job_setup_blocked_reason_check CHECK (blocked_reason IN ('SOURCE_NOT_PUBLIC', 'NO_ADMIN_PERMISSION',
        'RELINK_GITHUB'));
