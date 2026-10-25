-- Story 19.1 (FR-1, AD-19): one account per person, signed in with Google, with no role chosen at
-- sign-up. The Google subject is the account key, never the email. Each account has a kind: a USER
-- signs in with Google (or, for the rows from before this change and the tests, carries a GitHub ID),
-- an ADMINISTRATOR signs in with a password and TOTP and has neither. role stays until Story 20.3
-- drops it; a Google account has none. Personal data: google_email and google_name are exported, and
-- the three Google columns are cleared on erasure.

DO $$
DECLARE
    found RECORD;
BEGIN
    FOR found IN SELECT conname FROM pg_constraint
                 WHERE conrelid = 'identity.account'::regclass AND contype = 'c'
                   AND (pg_get_constraintdef(oid) LIKE '%github_user_id%'
                       OR pg_get_constraintdef(oid) LIKE '%mentor_approval_status IS NOT NULL%')
    LOOP
        EXECUTE format('ALTER TABLE identity.account DROP CONSTRAINT %I', found.conname);
    END LOOP;
END $$;

ALTER TABLE identity.account
    ADD COLUMN kind         TEXT,
    ADD COLUMN google_sub   TEXT UNIQUE,
    ADD COLUMN google_email TEXT,
    ADD COLUMN google_name  TEXT;

UPDATE identity.account SET kind = CASE WHEN role = 'ADMINISTRATOR' THEN 'ADMINISTRATOR' ELSE 'USER' END;

ALTER TABLE identity.account
    ALTER COLUMN kind SET NOT NULL,
    ALTER COLUMN role DROP NOT NULL,
    ADD CONSTRAINT account_kind_check CHECK (kind IN ('USER', 'ADMINISTRATOR')),
    -- An Administrator is exactly the ADMINISTRATOR kind, until role goes (Story 20.3).
    ADD CONSTRAINT account_kind_role_check CHECK ((kind = 'ADMINISTRATOR') = (role IS NOT DISTINCT FROM 'ADMINISTRATOR')),
    ADD CONSTRAINT account_mentor_status_check
        CHECK ((role IS NOT DISTINCT FROM 'MENTOR') = (mentor_approval_status IS NOT NULL)),
    -- A user that is not erased signs in with Google, or is a row from before Google sign-in.
    ADD CONSTRAINT account_user_identity_check
        CHECK (kind <> 'USER' OR erased_at IS NOT NULL OR google_sub IS NOT NULL OR github_user_id IS NOT NULL),
    ADD CONSTRAINT account_administrator_identity_check
        CHECK (kind <> 'ADMINISTRATOR' OR (google_sub IS NULL AND github_user_id IS NULL));

-- AD-19: each new Google sign-in address is a person-link signal too.
DO $$
DECLARE
    found RECORD;
BEGIN
    FOR found IN SELECT conname FROM pg_constraint
                 WHERE conrelid = 'identity.person_signal'::regclass AND contype = 'c'
                   AND pg_get_constraintdef(oid) LIKE '%GITHUB_EMAIL%'
    LOOP
        EXECUTE format('ALTER TABLE identity.person_signal DROP CONSTRAINT %I', found.conname);
    END LOOP;
END $$;

ALTER TABLE identity.person_signal
    ADD CONSTRAINT person_signal_source_check
        CHECK (source IN ('GOOGLE_EMAIL', 'GITHUB_EMAIL', 'SCHOOL_EMAIL', 'LINKEDIN'));
