-- Story 24.2b (D1, FR-22, FR-23, FR-24, FR-25, FR-26, FR-76 removed, FR-28, FR-32, FR-48, FR-50, FR-62, FR-69,
-- FR-76, FR-79, FR-82, FR-84, NFR-3, NFR-10, AD-2, AD-3, AD-8, AD-20, AD-23, AD-33, AD-39 removed, AD-41): vibefix
-- tracks no task of its own any more. The Board, Work Items, their history, the Review Gate, the workload view,
-- Attachments and notes are retired with the module vn.vibefix.work, so the schema goes with them. The fourteen
-- work/V2026* files before this one are superseded; they stay on disk as Flyway history, and AD-3's layer entry for
-- the module goes in the same change.
--
-- The seven tables are dropped by name first, so this file can say what each one loses, and only then is the schema
-- dropped with CASCADE for anything they left behind (indexes, grants, the sequences of their seq columns). That
-- CASCADE is safe: Flyway's own history and the Spring Modulith framework tables live in schema platform
-- (application.yaml, flyway.schemas: platform, AD-2), never in a module schema, and no table outside work ever
-- referenced one of these (the only foreign keys into them are work's own).
--
-- What each table loses:
--   work.work_item_commit  -- which Work Item each Handover Commit's message mentioned (FR-23). The commits
--                             themselves stay in delivery.handover_commit with their SHAs and messages; only the
--                             link goes, and vn.vibefix.shared.WorkItemKey with it.
--   work.work_item_history -- every submission, review, move, note and Attachment entry of every Work Item (FR-25,
--                             FR-76, FR-84), with the notes' text and the reviewers' comments.
--   work.attachment        -- every Attachment row of a Work Item: its file or its https link, its caption, and
--                             which upload it pointed at. The bytes go with the files rows in the same story.
--   work.work_item         -- every Work Item: its {JOBCODE}-{n} key, title, description, status, assignee,
--                             Milestone, Acceptance Criterion and edit version (FR-22, FR-23, AD-33).
--   work.admin_read_grant  -- the Administrator read grants this module served. WORK_ITEMS, WORK_ITEM_NOTES and
--                             ATTACHMENTS leave shared.AdminReadScope in the same change, so chat and job strip
--                             those items out of their own stored grants; nothing is re-homed, and a case's grant
--                             now opens the two Chats and a Milestone Split's versions only (AD-20, R41).
--   work.split_deferral    -- the Product deletions and retention runs this module was still holding for an open
--                             Milestone Split (Story 22.1, A-01). Nothing waits on the Board any more: the split's
--                             evidence is its versions, the Handover Commits and the Crew Chat.
--   work.board             -- every Job's Board with its Job Code and the next Work Item number (FR-22).
--
-- What stays: every audit entry this module ever wrote (work.admin_read, work.work_item_*, work.pages_shared and
-- the rest) keeps its row in audit, as AD-8 keeps them. Only the module that would write new ones goes.
-- Personal data: rows deleted. A participant's export and erasure lose their work section in the same change
-- (WorkPersonalData goes with the module), so nobody is left naming a table that is gone; nothing new is kept.

DROP TABLE work.work_item_commit;

DROP TABLE work.work_item_history;

DROP TABLE work.attachment;

DROP TABLE work.work_item;

DROP TABLE work.admin_read_grant;

DROP TABLE work.split_deferral;

DROP TABLE work.board;

DROP SCHEMA work CASCADE;
