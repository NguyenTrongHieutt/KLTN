-- Story 19.7 (AD-27, FR-74 removed): the notifications and to-dos of the retired Mentor applications,
-- Mentor decisions, approval revocations, student cards, and verification revocations have no type and
-- no text any more, so their rows go.

DELETE FROM notification.todo WHERE todo_key = 'identity.complete_mentor_application';

DELETE FROM notification.notification
WHERE type IN ('identity.student_card_approved', 'identity.student_card_rejected',
               'identity.mentor_application_received', 'identity.mentor_approved', 'identity.mentor_rejected',
               'identity.verification_revoked', 'identity.mentor_approval_revoked');
