-- Story 23.9a (FR-37, R1, AD-6, AD-27): the five Merge-App email templates are gone with their notifications, so an
-- email still queued under one, or a failed one an Administrator could retry, would abort with TEMPLATE_MISSING.
-- Personal data: the queued emails go with the personal data they hold; nothing new is kept.
--
-- repo.final_merge keeps its type and its payload ({jobId, authorizationId}), which the rewritten handler reads
-- unchanged: an operation of this type still out dispatches the run on the Customer's Actions instead of pushing and
-- merging itself, and one already ABORTED or SUCCEEDED is history.

DELETE FROM operations.external_operation
WHERE type = 'notification.email' AND status IN ('PENDING', 'RUNNING', 'FAILED')
  AND payload ->> 'template' IN ('repo.final_merge_authorization_pending', 'repo.final_merge_authorization_reminder',
                                 'repo.final_merge_authorized', 'repo.merge_app_request_refused',
                                 'repo.merge_app_selection_widened');
