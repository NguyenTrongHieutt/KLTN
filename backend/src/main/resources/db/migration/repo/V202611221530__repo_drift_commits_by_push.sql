-- Story 24.6 (FR-20, FR-53, AD-12): the Source App's push deliveries name a Source Repository's new commits, and
-- nothing reads or keeps a head any more. A Job's COMMITS warning is now one row with no head_sha: the first push after
-- the Customer's confirmation raises it, and every later push adds its commits to that row's list in place. So the
-- check that a COMMITS row names a head goes, found by its definition whatever its name. Rows of the retired daily
-- poll keep their head_sha, and drift_warning_change_idx still tells them from the Job's one row without a head.
-- Personal data: none (commit SHAs).

DO $$
DECLARE
    found RECORD;
BEGIN
    FOR found IN SELECT conname FROM pg_constraint
                 WHERE conrelid = 'repo.drift_warning'::regclass AND contype = 'c'
                   AND pg_get_constraintdef(oid) LIKE '%head_sha IS NOT NULL%'
    LOOP
        EXECUTE format('ALTER TABLE repo.drift_warning DROP CONSTRAINT %I', found.conname);
    END LOOP;
END $$;
