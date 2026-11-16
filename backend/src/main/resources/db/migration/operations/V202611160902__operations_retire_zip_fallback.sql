-- Story 23.10c (FR-38, D10, AD-6, AD-27): the ZIP Fallback and its handler are gone, so an operation of type
-- repo.zip_fallback still out would find no handler on its lane and an Administrator could retry it forever. Only
-- the rows that could still run go -- PENDING, RUNNING, FAILED -- as Story 23.9a's V202611141703 did for the
-- retired emails. One already SUCCEEDED, ABORTED or OBSOLETE is the history of a Job that has ended, and this
-- table keeps its rows rather than deleting them (V202610191200:10), so it stays as it is.
--
-- The fallback's email goes the same way: repo.zip_fallback_ready has no template left, so an email still queued
-- under it, or a failed one an Administrator could retry, would abort with TEMPLATE_MISSING.
-- Personal data: the queued emails go with the personal data they hold; nothing new is kept.

DELETE FROM operations.external_operation
WHERE type = 'repo.zip_fallback' AND status IN ('PENDING', 'RUNNING', 'FAILED');

DELETE FROM operations.external_operation
WHERE type = 'notification.email' AND status IN ('PENDING', 'RUNNING', 'FAILED')
  AND payload ->> 'template' = 'repo.zip_fallback_ready';
