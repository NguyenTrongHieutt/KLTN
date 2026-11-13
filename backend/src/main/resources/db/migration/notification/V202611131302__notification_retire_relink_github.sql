-- Story 23.5d (R31, AD-10, AD-27, UX-DR118): no GitHub linking token is kept, so nothing lapses and nobody is
-- asked to re-link GitHub. The identity.relink_github to-do and notification have no type and no text any more,
-- so their rows go, open or closed, read or not, and no raw key reaches a screen.
-- Personal data: rows deleted; nothing new is kept.

DELETE FROM notification.todo WHERE todo_key = 'identity.relink_github';

DELETE FROM notification.notification WHERE type = 'identity.relink_github';
