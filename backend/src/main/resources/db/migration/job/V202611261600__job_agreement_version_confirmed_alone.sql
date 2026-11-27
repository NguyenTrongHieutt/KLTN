-- FR-17, FR-85, AD-47 (exception 3, B-11), Story 25.3: in a Self-mentored Job nobody shares a draft. The Customer, who
-- is also the Mentor, confirms the live draft in one step, and the confirmed Agreement still names the draft it was
-- made from (job.confirmed_agreement.draft_version_no). That draft is kept as a version of its own kind,
-- CONFIRMED_ALONE: nobody reviewed it, and no comment, change request or approval ever names it.

ALTER TABLE job.agreement_version DROP CONSTRAINT agreement_version_status_check;

ALTER TABLE job.agreement_version
    ADD CONSTRAINT agreement_version_status_check CHECK (status IN ('SHARED', 'CONFIRMED_ALONE'));
