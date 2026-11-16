-- Story 23.11a (R6, AD-11, AD-44, AD-45, FR-18): an Assessment Job runs J7's Setup too, and makes one repository.
-- Until now only a Code Job had a Setup, and an Assessment Job reached J7 in M1's funding cascade with no repository
-- at all, so the Mentor and the Crew read the Customer's Source Repository directly -- which a private Source does not
-- allow. From here the same Setup runs for both kinds: the same Source checks, the same self-confirmation of the head,
-- the same copy on the Customer's own GitHub Actions. An assessment row marks itself with assessment = true and makes
-- exactly one private repository, the Intermediary, with no Working Repository in any column; J7 applies as soon as
-- that one repository reads back at the confirmed head, with no invitations and no Verify (those are Story 23.11b).
-- The CHECKs that name a step by working_at or copied_at go by their definition, whatever their names, and come back
-- reading the Intermediary for an assessment row and the Working Repository for a Code Job's. The blocked-reason CHECK
-- comes back with WORKFLOW_TOO_OLD: a Source whose vibefix.yml is an accepted version published before the
-- one-repository copy cannot run an Assessment Job's copy, so the Customer re-publishes the file and tries again.
-- Old rows: every Setup there is belongs to a Code Job, so the default false is right for all of them.
-- Personal data: none; repo.job_setup holds internal IDs, repository names and times only.

ALTER TABLE repo.job_setup
    ADD COLUMN assessment BOOLEAN NOT NULL DEFAULT FALSE;

DO $$
DECLARE
    found RECORD;
BEGIN
    FOR found IN SELECT conname FROM pg_constraint
                 WHERE conrelid = 'repo.job_setup'::regclass AND contype = 'c'
                   AND pg_get_constraintdef(oid) ~ '(working_at|copied_at|SOURCE_APP_WRONG_REPOSITORY)'
    LOOP
        EXECUTE format('ALTER TABLE repo.job_setup DROP CONSTRAINT %I', found.conname);
    END LOOP;
END $$;

ALTER TABLE repo.job_setup
    ADD CONSTRAINT job_setup_blocked_reason_check CHECK (blocked_reason IN ('SOURCE_APP_MISSING',
        'SOURCE_APP_WRONG_REPOSITORY', 'WORKFLOW_MISSING', 'WORKFLOW_MISMATCH', 'WORKFLOW_TOO_OLD',
        'NO_ADMIN_PERMISSION', 'EXCHANGE_REFUSED', 'RUN_FAILED', 'ACTIONS_QUOTA')),
    -- An Assessment Job has no Working Repository at all; a Code Job's is created and read back after the Intermediary.
    ADD CONSTRAINT job_setup_working_check CHECK ((NOT assessment OR (working_github_id IS NULL AND working_name IS NULL
        AND working_at IS NULL)) AND (working_at IS NULL
        OR (intermediary_at IS NOT NULL AND working_github_id IS NOT NULL))),
    -- Nothing is dispatched before every repository the copy pushes to was read back.
    ADD CONSTRAINT job_setup_run_check CHECK ((correlation_id IS NULL) = (dispatched_at IS NULL)
        AND (correlation_id IS NULL OR (CASE WHEN assessment THEN intermediary_at ELSE working_at END) IS NOT NULL)
        AND ((run_status IS NULL AND run_conclusion IS NULL) OR correlation_id IS NOT NULL)),
    ADD CONSTRAINT job_setup_copied_check CHECK (copied_at IS NULL
        OR (CASE WHEN assessment THEN intermediary_at ELSE working_at END) IS NOT NULL),
    ADD CONSTRAINT job_setup_done_check CHECK (status <> 'DONE' OR copied_at IS NOT NULL);
