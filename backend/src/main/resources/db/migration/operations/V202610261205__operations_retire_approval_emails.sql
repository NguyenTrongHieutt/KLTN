-- Story 19.7 (AD-6, AD-27): the email templates of the Mentor decisions, the approval revocation, the
-- student card decisions, and the verification revocation are gone. An email still queued under one of
-- them would abort with TEMPLATE_MISSING, so it goes, with the personal data it holds.

DELETE FROM operations.external_operation
WHERE type = 'notification.email' AND status IN ('PENDING', 'RUNNING')
  AND payload ->> 'template' IN ('identity.mentor_approved', 'identity.mentor_rejected',
                                 'identity.mentor_approval_revoked', 'identity.student_card_approved',
                                 'identity.student_card_rejected', 'identity.verification_revoked');
