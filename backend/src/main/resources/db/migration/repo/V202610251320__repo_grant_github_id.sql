-- Story 19.2 (AD-10 "Logins by ID", AD-11, AD-12): a grantee is known by their permanent numeric
-- GitHub ID, and the login is resolved from it just before each invitation, so a renamed GitHub
-- account keeps its access. github_login stays as the login last used. Grants from before this change
-- get their ID from the grantee's account the first time repo uses them, while the login still
-- matches, ignoring case (repo reads accounts through identity, never its tables).
-- Personal data: github_user_id is the grantee's, exported and deleted with the row (RepoPersonalData).

ALTER TABLE repo.job_grant
    ADD COLUMN github_user_id BIGINT;

CREATE INDEX job_grant_github_user_idx ON repo.job_grant (github_user_id);

-- "Sign in again" became "re-link GitHub" (AD-10): a Setup blocked on the Customer's GitHub says so.
DO $$
DECLARE
    found RECORD;
BEGIN
    FOR found IN SELECT conname FROM pg_constraint
                 WHERE conrelid = 'repo.job_setup'::regclass AND contype = 'c'
                   AND pg_get_constraintdef(oid) LIKE '%SIGN_IN_AGAIN%'
    LOOP
        EXECUTE format('ALTER TABLE repo.job_setup DROP CONSTRAINT %I', found.conname);
    END LOOP;
END $$;

UPDATE repo.job_setup SET blocked_reason = 'RELINK_GITHUB' WHERE blocked_reason = 'SIGN_IN_AGAIN';

ALTER TABLE repo.job_setup
    ADD CONSTRAINT job_setup_blocked_reason_check CHECK (blocked_reason IN ('INTAKE_FINDING_OPEN', 'SOURCE_NOT_PUBLIC',
        'NO_ADMIN_PERMISSION', 'RELINK_GITHUB'));
