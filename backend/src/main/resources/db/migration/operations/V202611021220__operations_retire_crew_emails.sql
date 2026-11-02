-- Story 21.5 (AD-6, AD-27): the email templates of the Crew Terms and the Crew changes are gone. An email still queued
-- under one of them would abort with TEMPLATE_MISSING, so it goes, with the personal data it holds.

DELETE FROM operations.external_operation
WHERE type = 'notification.email' AND status IN ('PENDING', 'RUNNING')
  AND payload ->> 'template' IN ('job.crew_assigned', 'job.crew_change_proposed', 'job.crew_changed',
                                 'job.crew_member_declined', 'job.crew_member_removed', 'job.crew_reconfirm_needed',
                                 'job.crew_terms_changed');
