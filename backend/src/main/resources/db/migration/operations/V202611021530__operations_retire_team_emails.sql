-- Story 21.7 (AD-6, AD-27): the Team email templates are gone. An email still queued under one of them would abort
-- with TEMPLATE_MISSING, so it goes, with the personal data it holds.

DELETE FROM operations.external_operation
WHERE type = 'notification.email' AND status IN ('PENDING', 'RUNNING')
  AND payload ->> 'template' IN ('team.invitation_received', 'team.join_request_received', 'team.join_request_answered',
                                 'team.released', 'team.member_removed');
