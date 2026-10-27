-- AD-43, AD-41 (Story 20.2): one role per Job. A Job's Mentor is never its Customer, and neither of them
-- holds a counted Crew row on it: a row that is not released, or that was released after it was activated
-- (CrewService.countedRow uses the same rule). Until Story 20.4 removes INACTIVE, that also counts an
-- unreleased INACTIVE row that was never activated, which job.roleOf reads as NONE. The commands refuse first,
-- under the Job row lock, with GUARD_FAILED ROLE_TAKEN; these constraints are only the backstop. The Crew
-- row's account column becomes member_account_id (AD-41); only the column is renamed.
-- Personal data: unchanged; member_account_id names the same account as before (JobPersonalData).

ALTER TABLE job.crew_row
    RENAME COLUMN student_account_id TO member_account_id;

ALTER TABLE job.job
    ADD CONSTRAINT job_mentor_not_customer_check CHECK (mentor_account_id IS DISTINCT FROM customer_account_id);

CREATE FUNCTION job.refuse_crew_row_of_another_role() RETURNS TRIGGER
    LANGUAGE plpgsql AS
$$
BEGIN
    IF (NEW.status <> 'RELEASED' OR NEW.activated_at IS NOT NULL) AND EXISTS (SELECT 1 FROM job.job j
               WHERE j.id = NEW.job_id
               AND (j.customer_account_id = NEW.member_account_id OR j.mentor_account_id = NEW.member_account_id)) THEN
        RAISE EXCEPTION 'one role per Job: a Job''s Customer or Mentor is never on its Crew'
            USING ERRCODE = 'integrity_constraint_violation';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER crew_row_one_role
    BEFORE INSERT OR UPDATE OF job_id, member_account_id ON job.crew_row
    FOR EACH ROW EXECUTE FUNCTION job.refuse_crew_row_of_another_role();

CREATE FUNCTION job.refuse_mentor_of_another_role() RETURNS TRIGGER
    LANGUAGE plpgsql AS
$$
BEGIN
    IF NEW.mentor_account_id IS NOT NULL AND EXISTS (SELECT 1 FROM job.crew_row r WHERE r.job_id = NEW.id
               AND r.member_account_id = NEW.mentor_account_id
               AND (r.status <> 'RELEASED' OR r.activated_at IS NOT NULL)) THEN
        RAISE EXCEPTION 'one role per Job: a Mentor is never selected while on the Job''s Crew'
            USING ERRCODE = 'integrity_constraint_violation';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER job_mentor_one_role
    BEFORE UPDATE OF mentor_account_id ON job.job
    FOR EACH ROW EXECUTE FUNCTION job.refuse_mentor_of_another_role();
