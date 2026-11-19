-- Story 24.2b (D1, FR-25, FR-48, FR-59, AD-27): the Board is retired, so the four Work Item notices and the three
-- Work Item to-dos have no type, no text, no audience and no screen left. Their rows go, read or not, open or
-- closed, so no raw key reaches a list: a notification or a to-do whose key the catalog no longer knows is drawn
-- with its key instead of its title, and its link would lead to a route the SPA no longer has. V202611191004 did
-- the same for work.pages_shared one story earlier; it stays as Flyway history.
--
-- What this loses: the notices work.work_item_assigned, work.review_requested, work.work_item_approved and
-- work.work_item_returned leave every Mentor's and Crew member's list, and the to-dos work.review_work_item,
-- work.fix_returned_item and work.reassign_work_items leave every list of things to do. Nothing replaces them,
-- because no Work Item can be assigned, submitted, reviewed, returned or reassigned from now on: the Mentor plans
-- and reviews in tools of their own, and what the Customer sees is the Milestone Handover. There is no email
-- template to delete -- the three in-app notices were in-app only and work.work_item_returned's email text goes
-- with the type in the catalog.
--
-- What stays: the audit entries of every Work Item action (AD-8), and every notification and to-do of another type.
-- Personal data: rows deleted; nothing new is kept.

DELETE FROM notification.todo
WHERE todo_key IN ('work.review_work_item', 'work.fix_returned_item', 'work.reassign_work_items');

DELETE FROM notification.notification
WHERE type IN ('work.work_item_assigned', 'work.review_requested', 'work.work_item_approved',
               'work.work_item_returned');
