-- FR-85, AD-47, AD-43 (Story 25.2a): the Job's mode. In a Self-mentored Job the Customer is also the Mentor and
-- recruits the Crew directly; nobody bids. job.roleOf derives the combined role CUSTOMER_MENTOR from this column,
-- never from the two account columns being equal.
-- The check that kept the Mentor apart from the Customer becomes a check by mode: a self-mentored Job's Mentor is its
-- Customer, except while it is a Draft, when it has no Mentor yet (J11 or J12 sets both); a normal Job's Mentor is
-- never its Customer. So the mode may change freely on a Draft and, once the Mentor is set, only together with it.
-- The check that a Job past Open has its Mentor, the selected Application and the selection time is rewritten for
-- the mode, where no Application exists: the Mentor and the time stay required (in the mode the time is J11's or
-- J12's, when the Customer became the Mentor), and the Job names a selected Application if and only if it is normal.
-- Every existing Job is normal. The triggers that keep the Customer and the Mentor off the Job's Crew do not change.
-- Personal data: none.

ALTER TABLE job.job
    ADD COLUMN self_mentored BOOLEAN NOT NULL DEFAULT FALSE;

ALTER TABLE job.job
    DROP CONSTRAINT job_mentor_not_customer_check,
    DROP CONSTRAINT job_mentor_check;

ALTER TABLE job.job
    ADD CONSTRAINT job_mentor_by_mode_check CHECK (
        CASE
            WHEN self_mentored AND state = 'DRAFT' THEN mentor_account_id IS NULL
            WHEN self_mentored THEN mentor_account_id IS NOT DISTINCT FROM customer_account_id
            ELSE mentor_account_id IS DISTINCT FROM customer_account_id
        END),
    ADD CONSTRAINT job_mentor_check CHECK (
        (NOT self_mentored OR selected_application_id IS NULL)
        AND (state IN ('DRAFT', 'OPEN')
            OR (mentor_account_id IS NOT NULL AND selected_at IS NOT NULL
                AND (self_mentored OR selected_application_id IS NOT NULL))));
