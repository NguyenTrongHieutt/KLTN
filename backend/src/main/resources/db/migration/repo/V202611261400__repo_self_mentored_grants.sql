-- Story 25.2a part 4 (D11, AD-11, AD-47, B-10, R63): in a Self-mentored Job the Customer is also the Mentor, and that
-- account holds the Customer's grant only: read on the Intermediary Repository, and nothing with the team, read
-- included. "The Customer reading the Working Repository" is a prevented state for every Job.
-- repo keeps this in its own schema. The Setup row records the Job's mode, which job hands down with the Job when a
-- Setup starts (JobBusy.BusyJob.selfMentored); the mode never changes once a Job has a Setup, since J11 and J12 both
-- end before the Agreement. A trigger on repo.job_grant then refuses:
--   1. a row with the role MENTOR for a Job that has a self-mentored Setup: such a Job grants no Mentor;
--   2. a team row (MENTOR or CREW) for an account that holds a CUSTOMER row of the same Job, and a CUSTOMER row for an
--      account that holds a team row of it. For a Code Job the team's rows are the Working Repository's; for an
--      Assessment Job they are on its one copy, where the Customer's own read is their only grant.
-- The commands decide first (JobGrants.counted, JobGrants.grant, GrantInviteOperation); this is only the backstop, and
-- like the other role triggers it does not see a row another open transaction is writing.
-- Old rows: every Setup there is belongs to a normal Job, so the default false is right for all of them; the trigger
-- reads no old grant row again, and one role per Job (AD-43) already kept a Customer off their Job's team.
-- Personal data: none added; repo.job_setup still holds internal IDs, repository names and times only.

ALTER TABLE repo.job_setup
    ADD COLUMN self_mentored BOOLEAN NOT NULL DEFAULT FALSE;

CREATE FUNCTION repo.refuse_team_grant_of_the_customer() RETURNS TRIGGER
    LANGUAGE plpgsql AS
$$
BEGIN
    IF NEW.role = 'MENTOR' AND EXISTS (SELECT 1 FROM repo.job_setup s
               WHERE s.job_id = NEW.job_id AND s.self_mentored) THEN
        RAISE EXCEPTION 'a Self-mentored Job grants no Mentor: its Mentor is its Customer'
            USING ERRCODE = 'integrity_constraint_violation';
    END IF;
    IF EXISTS (SELECT 1 FROM repo.job_grant g
               WHERE g.job_id = NEW.job_id AND g.account_id = NEW.account_id AND g.id <> NEW.id
               AND (g.role = 'CUSTOMER') <> (NEW.role = 'CUSTOMER')) THEN
        RAISE EXCEPTION 'a Job''s Customer holds no grant with its team'
            USING ERRCODE = 'integrity_constraint_violation';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER job_grant_customer_never_on_the_team
    BEFORE INSERT OR UPDATE OF job_id, account_id, role ON repo.job_grant
    FOR EACH ROW EXECUTE FUNCTION repo.refuse_team_grant_of_the_customer();
