-- Story 23.7 (R5, AD-7, AD-11, AD-12, FR-18, FR-21, FR-59): the Customer reads the Job's private Intermediary
-- Repository from J7 until the repositories are deleted. Their access is a repo.job_grant row with the role CUSTOMER on
-- the Intermediary, invited with read at Setup's Invitations stage beside the team's Working invitations; it never
-- gates Verify, J7, the Final Merge, or "all revoked", and it never reaches WRITE: ACCEPTED is its settled state. A
-- Customer's read that ends without a revocation is LAPSED, a settled state only theirs: they declined the invitation,
-- left the repository after accepting, or the renewals ran out. The role and status CHECKs go by their definitions,
-- whatever their names, and come back with CUSTOMER and LAPSED; every other CHECK stays.
-- Personal data: the Customer's account, GitHub username and numeric ID, as for any grant; exported for them, revoked
-- on GitHub and deleted on their erasure, and deleted with the Product (RepoPersonalData).

DO $$
DECLARE
    found RECORD;
BEGIN
    FOR found IN SELECT conname FROM pg_constraint
                 WHERE conrelid = 'repo.job_grant'::regclass AND contype = 'c'
                   AND (pg_get_constraintdef(oid) LIKE '%CREW%' OR pg_get_constraintdef(oid) LIKE '%MEMBER_DRIFT%')
    LOOP
        EXECUTE format('ALTER TABLE repo.job_grant DROP CONSTRAINT %I', found.conname);
    END LOOP;
END $$;

ALTER TABLE repo.job_grant
    ADD CONSTRAINT job_grant_role_check CHECK (role IN ('MENTOR', 'CREW', 'CUSTOMER')),
    ADD CONSTRAINT job_grant_status_check CHECK (status IN ('INVITING', 'INVITED', 'ACCEPTED', 'MEMBER_DRIFT', 'WRITE',
        'REVOKING', 'REVOKED', 'LAPSED')),
    ADD CONSTRAINT job_grant_customer_read_check CHECK (role <> 'CUSTOMER' OR status <> 'WRITE'),
    ADD CONSTRAINT job_grant_lapsed_check CHECK (status <> 'LAPSED' OR role = 'CUSTOMER');
