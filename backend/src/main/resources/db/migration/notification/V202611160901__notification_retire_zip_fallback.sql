-- Story 23.10c (FR-38, D10, UX-DR118): the ZIP Fallback is retired, so repo.zip_fallback_ready has no type, no
-- text and no email template any more. Its rows go, read or not, so no raw key reaches a screen: a notification
-- whose type the catalog no longer knows would be drawn with its key instead of its title.
--
-- What this loses: a Job that ended by the fallback before this story loses that one notice from its list. Nothing
-- else: the Job keeps its ended state and its audit, and the Customer's own closure section still names how the
-- Job ended. No notification replaces it, because no Job can end that way from now on.
-- Personal data: rows deleted; nothing new is kept.

DELETE FROM notification.notification WHERE type = 'repo.zip_fallback_ready';
