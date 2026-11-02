-- Story 21.5 (FR-75, FR-81, AD-3, AD-4, AD-41, AD-43): the Crew comes from Crew Applications. A pick inserts an
-- ASSIGNED row that names its Crew Application (crew_application_id, matching's, never a foreign key across modules);
-- the person's signature makes it ACTIVE, and an unsigned pick ends RELEASED: DECLINED when the person declines or
-- the signing period passes, WITHDRAWN when the Mentor withdraws it ("Rút lựa chọn"), JOB_ENDED at J8. REMOVED stays
-- for rows released by the retired Team picker; nothing writes it any more.
-- A person is activated on a Job at most once (no rejoining, R3): the partial index finds an earlier activation, and a
-- trigger refuses a second one as the backstop of job.activateCrew's own refusal (CREW_REJOIN_REFUSED). It checks new
-- activations only, so rows activated before this story stay as they are.
-- The retired Team picker's rows that still wait for the Crew Terms confirmation can never be signed now (Crew Terms
-- are no longer written), so they are released as WITHDRAWN and their people are free again.
-- Personal data: unchanged; the rows keep naming the same accounts (JobPersonalData). The application ID is an
-- identifier only.

ALTER TABLE job.crew_row
    ADD COLUMN crew_application_id UUID;

CREATE UNIQUE INDEX crew_row_application_idx ON job.crew_row (crew_application_id)
    WHERE crew_application_id IS NOT NULL;

ALTER TABLE job.crew_row
    DROP CONSTRAINT crew_row_released_reason_check;

ALTER TABLE job.crew_row
    ADD CONSTRAINT crew_row_released_reason_check
        CHECK (released_reason IN ('DECLINED', 'WITHDRAWN', 'LEFT', 'JOB_ENDED', 'REMOVED'));

CREATE INDEX crew_row_activated_idx ON job.crew_row (job_id, member_account_id) WHERE activated_at IS NOT NULL;

CREATE FUNCTION job.refuse_crew_rejoin() RETURNS TRIGGER
    LANGUAGE plpgsql AS
$$
BEGIN
    IF NEW.activated_at IS NOT NULL AND (TG_OP = 'INSERT' OR OLD.activated_at IS NULL) AND EXISTS (
            SELECT 1 FROM job.crew_row r
            WHERE r.job_id = NEW.job_id AND r.member_account_id = NEW.member_account_id AND r.id <> NEW.id
              AND r.activated_at IS NOT NULL) THEN
        RAISE EXCEPTION 'no rejoining: a person is activated on a Job''s Crew at most once'
            USING ERRCODE = 'integrity_constraint_violation';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER crew_row_no_rejoin
    BEFORE INSERT OR UPDATE OF activated_at ON job.crew_row
    FOR EACH ROW EXECUTE FUNCTION job.refuse_crew_rejoin();

UPDATE job.crew_row
SET status          = 'RELEASED',
    released_at     = now(),
    released_reason = 'WITHDRAWN'
WHERE status = 'ASSIGNED'
  AND crew_application_id IS NULL;
