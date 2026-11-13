-- Story 23.5d (R31, AD-10, AD-13): no GitHub linking token is kept, so a Setup can no longer block on the Customer
-- re-linking GitHub, and RELINK_GITHUB goes. The Source checks read through the Source App since Story 23.5, so a
-- Setup an old row still shows blocked that way maps to SOURCE_APP_MISSING, which the Customer fixes on CUS-4. The
-- CHECK that names the retired reason goes by its definition, whatever its name, and comes back without it.
-- Personal data: none.

DO $$
DECLARE
    found RECORD;
BEGIN
    FOR found IN SELECT conname FROM pg_constraint
                 WHERE conrelid = 'repo.job_setup'::regclass AND contype = 'c'
                   AND pg_get_constraintdef(oid) LIKE '%RELINK_GITHUB%'
    LOOP
        EXECUTE format('ALTER TABLE repo.job_setup DROP CONSTRAINT %I', found.conname);
    END LOOP;
END $$;

UPDATE repo.job_setup SET blocked_reason = 'SOURCE_APP_MISSING' WHERE blocked_reason = 'RELINK_GITHUB';

ALTER TABLE repo.job_setup
    ADD CONSTRAINT job_setup_blocked_reason_check CHECK (blocked_reason IN ('SOURCE_APP_MISSING',
        'SOURCE_APP_WRONG_REPOSITORY', 'WORKFLOW_MISSING', 'WORKFLOW_MISMATCH', 'NO_ADMIN_PERMISSION'));
