-- Story 23.1 (D8, R16, AD-27, UX-DR118): vibefix scans no code, so the scanning notifications (an intake scan
-- clean, a Finding opened or reopened, a push scan delayed) and the two Finding to-dos have no type and no text any
-- more. Their rows go, open or closed, read or not, so no raw key reaches a screen.
-- Personal data: rows deleted; nothing new is kept.

DELETE FROM notification.todo WHERE todo_key IN ('scanning.resolve_intake_finding', 'scanning.resolve_work_finding');

DELETE FROM notification.notification WHERE type LIKE 'scanning.%';
