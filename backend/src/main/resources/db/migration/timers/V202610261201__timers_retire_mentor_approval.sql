-- Story 19.7 (AD-7, AD-19): the Deadlines of the retired Mentor applications and student cards have no
-- handler any more, so their rows go, pending or finished, and the poller never meets their kinds.

DELETE FROM timers.deadline
WHERE kind IN ('identity.mentor_reapply_wait', 'identity.mentor_application_purge',
               'identity.mentor_document_deletion', 'identity.student_card_deletion');
