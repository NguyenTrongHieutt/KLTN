-- Story 23.1 (D8, R16, AD-6, AD-27): vibefix scans no code, so the scanning.scan operation, its handler and the scan
-- lane are gone, and so are the email templates of the scanning notifications.
-- Personal data: the queued emails go with the personal data they hold; nothing new is kept.

-- A scan operation of any status would name a scan run that no longer exists, and a queued or failed one would make
-- the worker throw on a type it no longer knows.
DELETE FROM operations.external_operation WHERE type = 'scanning.scan';

-- No other operation ever ran in the scan lane.
DO $$
DECLARE
    found RECORD;
BEGIN
    FOR found IN SELECT conname FROM pg_constraint
                 WHERE conrelid = 'operations.external_operation'::regclass AND contype = 'c'
                   AND pg_get_constraintdef(oid) LIKE '%''SCAN''%'
    LOOP
        EXECUTE format('ALTER TABLE operations.external_operation DROP CONSTRAINT %I', found.conname);
    END LOOP;
END $$;

ALTER TABLE operations.external_operation
    ADD CONSTRAINT external_operation_lane_check CHECK (lane IN ('GITHUB_LONG', 'GITHUB_SHORT', 'EMAIL', 'VNPAY'));

-- An email still queued under a scanning template, or a failed one an Administrator could retry, would abort with
-- TEMPLATE_MISSING.
DELETE FROM operations.external_operation
WHERE type = 'notification.email' AND status IN ('PENDING', 'RUNNING', 'FAILED')
  AND payload ->> 'template' LIKE 'scanning.%';

-- A J7 Setup an open Intake Finding blocked is FAILED now (repo's migration of this story), and its operation ended
-- ABORTED, which is final. It is moved to FAILED, so ADM-7 lists it and an Administrator's retry (FR-64) runs it
-- again: the attempt then re-checks the Source Repository. The row is re-inserted unchanged but for its status,
-- because the guard trigger refuses any update of a final operation.
WITH unblocked AS (
    DELETE FROM operations.external_operation
    WHERE type = 'repo.job_setup' AND status = 'ABORTED' AND error_code = 'INTAKE_FINDING_OPEN'
    RETURNING *
)
INSERT INTO operations.external_operation (id, operation_key, type, lane, priority, status, payload, personal_data,
                                           account_id, attempt_count, next_attempt_at, lease_until, read_back_first,
                                           result, error_code, created_at, completed_at)
SELECT id, operation_key, type, lane, priority, 'FAILED', payload, personal_data, account_id, attempt_count,
       next_attempt_at, lease_until, read_back_first, result, error_code, created_at, completed_at
FROM unblocked;
