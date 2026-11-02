-- Story 21.5 (FR-81, AD-3, AD-7, AD-41, AD-43, R4): the Mentor picks a pending Crew Application. The pick goes through
-- job.addCrew first, under the Job row lock, and only then is the application PICKED: picked_at, the ASSIGNED Crew
-- row it made (crew_row_id, job's, an identifier only) and sign_due_at, when the 3-day commitment signature period
-- ends (a non-pausable Deadline). A picked application ends when the person signs (JOINED, end_reason SIGNED), declines
-- (DECLINED, COMMITMENT_DECLINED), lets the period pass (LAPSED, COMMITMENT_LAPSED), the Mentor withdraws the pick
-- (WITHDRAWN, PICK_WITHDRAWN), a lock or an erasure withdraws it (WITHDRAWN, APPLICANT_UNAVAILABLE), or the Job enters
-- Closing or is cancelled before the signature (LAPSED, PICK_ENDED). A pending application also lapses when its
-- applicant signs on another Job's Crew (JOINED_ANOTHER_CREW, A-16). One open application (pending or picked) per Job and
-- applicant stays a unique key.
-- The person's signature is matching's evidence: the NDA row of matching.nda_signature gains the commitment text's
-- version (commitment_version), signed at the same time; the selected Mentor's NDA has none.
-- Personal data: the new columns are the applicant's application and signature records, exported and deleted with
-- them as before (MatchingPersonalData). A joined application is no unpicked one: it stays with the Job's data rather
-- than being deleted 90 days after the decision (X3), so it carries no purge_due_at.

ALTER TABLE matching.crew_application
    ADD COLUMN crew_row_id UUID,
    ADD COLUMN picked_at   TIMESTAMPTZ,
    ADD COLUMN sign_due_at TIMESTAMPTZ;

ALTER TABLE matching.crew_application
    DROP CONSTRAINT crew_application_status_check,
    DROP CONSTRAINT crew_application_end_reason_check,
    DROP CONSTRAINT crew_application_ended_check;

ALTER TABLE matching.crew_application
    ADD CONSTRAINT crew_application_status_check
        CHECK (status IN ('PENDING', 'PICKED', 'JOINED', 'DECLINED', 'WITHDRAWN', 'LAPSED')),
    ADD CONSTRAINT crew_application_end_reason_check
        CHECK (end_reason IN ('DECLINED', 'WITHDRAWN', 'APPLICANT_UNAVAILABLE', 'EXPIRED', 'RECRUITMENT_CLOSED',
                              'JOB_CLOSING', 'JOB_CANCELLED', 'JOB_HIDDEN', 'JOINED_ANOTHER_CREW', 'PICK_WITHDRAWN',
                              'COMMITMENT_DECLINED', 'COMMITMENT_LAPSED', 'PICK_ENDED', 'SIGNED')),
    ADD CONSTRAINT crew_application_ended_check
        CHECK ((status IN ('PENDING', 'PICKED')) = (ended_at IS NULL AND end_reason IS NULL)),
    ADD CONSTRAINT crew_application_pick_check
        CHECK ((picked_at IS NULL) = (crew_row_id IS NULL) AND (picked_at IS NULL) = (sign_due_at IS NULL)),
    ADD CONSTRAINT crew_application_picked_check
        CHECK (status NOT IN ('PICKED', 'JOINED') OR picked_at IS NOT NULL),
    ADD CONSTRAINT crew_application_joined_check
        CHECK ((status = 'JOINED') = (end_reason IS NOT DISTINCT FROM 'SIGNED'));

DROP INDEX matching.crew_application_pending_idx;

CREATE UNIQUE INDEX crew_application_open_idx ON matching.crew_application (job_id, applicant_account_id)
    WHERE status IN ('PENDING', 'PICKED');

ALTER TABLE matching.nda_signature
    ADD COLUMN commitment_version TEXT CHECK (char_length(commitment_version) BETWEEN 1 AND 20);

GRANT UPDATE ON matching.nda_signature TO ${runtime_role};
