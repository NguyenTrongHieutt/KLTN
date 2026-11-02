-- Story 21.7 (AD-27, AD-37, UX-DR112): the Team notifications and the student verification's to-do have no type and
-- no text any more, so their rows go, open or closed, and no raw key reaches a screen. No team.* to-do key was ever
-- built, and the N-26 consent is a notice, kept in identity. Personal data: rows deleted; nothing new is kept.

DELETE FROM notification.todo WHERE todo_key = 'identity.verify_student';

DELETE FROM notification.notification WHERE type LIKE 'team.%' OR type = 'identity.verify_student';
