-- FR-42, FR-83, AD-15, AD-29, AD-33, AD-47 (Story 25.6): the split of a Self-mentored Job is a split among the Crew
-- members only. The Customer, who is the Mentor there, is its drafter and never a party, so the Customer's payment never
-- flows back to the Customer.
-- self_mentored is the split's own mode mark, set at the release from the Job's column and never changed: it tells the
-- drafter apart from a party after the Job's row went. In the mode mentor_account_id names the drafter and no party row
-- is a Mentor's; with one party the split is final at once as SOLE_MEMBER. The party at position 0 receives the rounding
-- remainder in every Job: the Mentor in a normal Job, and in the mode the first of the positions the release orders by
-- the commitment signature.
-- The trigger keeps two rules the table's CHECK could not: a normal split's Mentor is its party 0 and a mode split has no
-- Mentor row; and no party row ever names the Job's Customer, in any Job.
-- Personal data: nothing new. The mark is a flag beside rows an erasure already anonymizes (JobPersonalData).

ALTER TABLE job.milestone_split
    ADD COLUMN self_mentored BOOLEAN NOT NULL DEFAULT FALSE;

ALTER TABLE job.milestone_split_version
    DROP CONSTRAINT milestone_split_version_kind_check,
    ADD CONSTRAINT milestone_split_version_kind_check
        CHECK (kind IN ('DRAFT', 'DEFAULT', 'DECISION', 'MENTOR_ONLY', 'SOLE_MEMBER'));

ALTER TABLE job.milestone_split
    DROP CONSTRAINT milestone_split_finality_check,
    ADD CONSTRAINT milestone_split_finality_check
        CHECK (finality IN ('CONFIRMED', 'DEADLINE', 'DECISION', 'MENTOR_ONLY', 'SOLE_MEMBER')),
    ADD CONSTRAINT milestone_split_sole_member_mode CHECK (self_mentored OR finality IS DISTINCT FROM 'SOLE_MEMBER'),
    ADD CONSTRAINT milestone_split_mentor_only_mode CHECK (NOT self_mentored OR finality IS DISTINCT FROM 'MENTOR_ONLY'),
    ADD CONSTRAINT milestone_split_mode_no_cap CHECK (NOT self_mentored OR NOT cap_applies);

ALTER TABLE job.milestone_split_party
    DROP CONSTRAINT milestone_split_party_check,
    ADD CONSTRAINT milestone_split_party_mentor_first CHECK (NOT is_mentor OR position = 0);

CREATE FUNCTION job.milestone_split_party_guard() RETURNS TRIGGER
    LANGUAGE plpgsql AS
$$
DECLARE
    mode_split BOOLEAN;
    split_job  UUID;
BEGIN
    SELECT s.self_mentored, s.job_id INTO mode_split, split_job FROM job.milestone_split s WHERE s.id = NEW.split_id;
    IF mode_split AND NEW.is_mentor THEN
        RAISE EXCEPTION 'the split of a Self-mentored Job has no Mentor party'
            USING ERRCODE = 'check_violation';
    END IF;
    IF NOT mode_split AND NEW.is_mentor <> (NEW.position = 0) THEN
        RAISE EXCEPTION 'the Mentor is party 0 of a split, and party 0 is the Mentor'
            USING ERRCODE = 'check_violation';
    END IF;
    IF NEW.party_account_id IS NOT NULL AND EXISTS (SELECT 1
                                                    FROM job.job j
                                                    WHERE j.id = split_job
                                                      AND j.customer_account_id = NEW.party_account_id) THEN
        RAISE EXCEPTION 'the Customer of a Job is never a party of its splits'
            USING ERRCODE = 'check_violation';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER milestone_split_party_guard
    BEFORE INSERT OR UPDATE ON job.milestone_split_party
    FOR EACH ROW EXECUTE FUNCTION job.milestone_split_party_guard();
