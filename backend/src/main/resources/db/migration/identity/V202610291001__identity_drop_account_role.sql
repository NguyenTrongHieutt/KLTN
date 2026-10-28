-- Story 20.6 (AD-19, AD-43, SG-56): no account has a role. A person's rights come from their role in each Job
-- and from their capabilities, and an Administrator is told by kind alone. The session view no longer returns
-- the role and the one shell no longer reads it, so its last reader is gone: the column goes with its checks.
-- The rows keep their kind, which Story 19.1 derived from the role.

ALTER TABLE identity.account DROP CONSTRAINT IF EXISTS account_kind_role_check;

-- The inline CHECK of the first identity migration, named by PostgreSQL.
DO $$
DECLARE
    found RECORD;
BEGIN
    FOR found IN SELECT conname FROM pg_constraint
                 WHERE conrelid = 'identity.account'::regclass AND contype = 'c'
                   AND pg_get_constraintdef(oid) LIKE '%role%'
    LOOP
        EXECUTE format('ALTER TABLE identity.account DROP CONSTRAINT %I', found.conname);
    END LOOP;
END $$;

ALTER TABLE identity.account DROP COLUMN role;
