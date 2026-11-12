-- Story 23.5 (AD-10, AD-13, AD-45, R2, R7): the Source checks read the Source Repository through the Customer's
-- Source App, so a private repository is no longer a block and SOURCE_NOT_PUBLIC goes. A Setup blocks on the
-- Source App (SOURCE_APP_MISSING, SOURCE_APP_WRONG_REPOSITORY), the published workflow (WORKFLOW_MISSING,
-- WORKFLOW_MISMATCH), or the admin right; RELINK_GITHUB stays until Story 23.5b. A Final Merge that could no longer
-- read the Source routes to the ZIP Fallback as SOURCE_APP_MISSING. Old rows map to SOURCE_APP_MISSING first; the
-- CHECKs that name the retired reason go by their definition, whatever their names.

DO $$
DECLARE
    found RECORD;
BEGIN
    FOR found IN SELECT conrelid::regclass::text AS tbl, conname FROM pg_constraint
                 WHERE conrelid IN ('repo.job_setup'::regclass, 'repo.zip_fallback'::regclass) AND contype = 'c'
                   AND pg_get_constraintdef(oid) LIKE '%SOURCE_NOT_PUBLIC%'
    LOOP
        EXECUTE format('ALTER TABLE %s DROP CONSTRAINT %I', found.tbl, found.conname);
    END LOOP;
END $$;

UPDATE repo.job_setup SET blocked_reason = 'SOURCE_APP_MISSING' WHERE blocked_reason = 'SOURCE_NOT_PUBLIC';

UPDATE repo.zip_fallback SET reason = 'SOURCE_APP_MISSING' WHERE reason = 'SOURCE_NOT_PUBLIC';

UPDATE repo.final_merge SET refusal = 'SOURCE_APP_MISSING' WHERE refusal = 'SOURCE_NOT_PUBLIC';

ALTER TABLE repo.job_setup
    ADD CONSTRAINT job_setup_blocked_reason_check CHECK (blocked_reason IN ('SOURCE_APP_MISSING',
        'SOURCE_APP_WRONG_REPOSITORY', 'WORKFLOW_MISSING', 'WORKFLOW_MISMATCH', 'NO_ADMIN_PERMISSION', 'RELINK_GITHUB'));

ALTER TABLE repo.zip_fallback
    ADD CONSTRAINT zip_fallback_reason_check CHECK (reason IN ('TRIAL_CONFLICT', 'SOURCE_APP_MISSING', 'J9_GUARD',
        'PUSH_REFUSED', 'MERGE_REFUSED', 'NOT_AUTHORIZED', 'REQUEST_NOT_APPROVED', 'CONFIRMATION_MISSED', 'DECLINED'));
