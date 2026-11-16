-- Story 23.10c (FR-38, D10, AD-6, AD-27): the ZIP Fallback and its handler are gone, so an operation of type
-- repo.zip_fallback still out would find no handler on its lane and an Administrator could retry it forever. Its
-- rows go, whatever their status: a finished one is history of a Job that already ended, and the Job keeps its
-- state and its audit (AD-8).
--
-- The fallback's email goes the same way: repo.zip_fallback_ready has no template left, so an email still queued
-- under it, or a failed one an Administrator could retry, would abort with TEMPLATE_MISSING.
-- Personal data: the queued emails go with the personal data they hold; nothing new is kept.

DELETE FROM operations.external_operation WHERE type = 'repo.zip_fallback';

DELETE FROM operations.external_operation
WHERE type = 'notification.email' AND status IN ('PENDING', 'RUNNING', 'FAILED')
  AND payload ->> 'template' = 'repo.zip_fallback_ready';
