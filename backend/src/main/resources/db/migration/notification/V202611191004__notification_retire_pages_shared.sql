-- Story 24.1 (D2, FR-48, FR-70): the Documentation Space is retired, so work.pages_shared has no type, no text and
-- no link left -- nothing shares Pages, and /cong-viec/{id}/tai-lieu-cu answers nothing. Its rows go, read or not, so
-- no raw key reaches a screen: a notification whose type the catalog no longer knows would be drawn with its key
-- instead of its title, and its link would lead to a dead route.
--
-- What this loses: a Job whose Customer shared earlier Pages before this story loses that one notice from the
-- Mentor's and the Crew's lists. Nothing replaces it, because no Job can share Pages from now on. The audit entries
-- work.pages_shared and work.pages_unshared stay, as AD-8 keeps them. There is no to-do and no email template to
-- delete: the notice was in-app only and opened nothing for anyone to do.
-- Personal data: rows deleted; nothing new is kept.

DELETE FROM notification.notification WHERE type = 'work.pages_shared';
