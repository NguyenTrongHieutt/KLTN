-- Story 19.7 (FR-74 removed, FR-59, FR-5, AD-19, AD-42, AD-24): Mentor applications, their decisions,
-- Mentor approval, and the student card are retired. Any person's account bids as a Mentor once GitHub is
-- linked (Q8); nobody applies, nobody is approved, and no Administrator reviews a card. The tables go with
-- their rows; their stored files are marked deleted by the files migration of this story, and the audit
-- entries about them stay untouched (AD-8).
-- The verification CHECK values STUDENT_CARD and REVOKED, and account_action's VERIFICATION_REVOKED and
-- APPROVAL_REVOKED, stay for the rows that already hold them: no code writes them any more.

DROP TABLE identity.mentor_application;
DROP TABLE identity.mentor_decision;
DROP TABLE identity.student_card;

ALTER TABLE identity.account
    DROP CONSTRAINT IF EXISTS account_mentor_status_check,
    DROP COLUMN mentor_approval_status;

-- A live verification by a student card ends with the card path (dev data only): its facts, the
-- profile's projection of them, and the account's pending school email codes (a renewal's holds a copy
-- of the locked name and major) go, as an ended row's do. The verify to-do is not reopened.
UPDATE identity.profile SET student_name = NULL, student_school = NULL, student_major = NULL
WHERE account_id IN (SELECT account_id FROM identity.verification
                     WHERE provider = 'STUDENT_CARD' AND ended_at IS NULL);

DELETE FROM identity.school_email_code
WHERE account_id IN (SELECT account_id FROM identity.verification
                     WHERE provider = 'STUDENT_CARD' AND ended_at IS NULL);

DELETE FROM identity.verification WHERE provider = 'STUDENT_CARD' AND ended_at IS NULL;
