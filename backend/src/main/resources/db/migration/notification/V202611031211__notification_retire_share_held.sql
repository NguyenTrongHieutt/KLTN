-- Story 22.4 (AD-27, UX-DR112): payment.share_held has no type and no text any more, since no release holds a share,
-- so its rows go, read or not, and no raw key reaches a screen. It never had an email or a to-do.
-- Personal data: rows deleted; nothing new is kept.

DELETE FROM notification.notification WHERE type = 'payment.share_held';
