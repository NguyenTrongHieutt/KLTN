-- Story 24.2b (D1, AD-8, AD-20, FR-53, FR-84, R39): the Board is retired, so AdminReadScope has no WORK_ITEMS,
-- WORK_ITEM_NOTES or ATTACHMENTS any more and a stored grant naming one could not be read back at all
-- (SplitVersionGrants and its sibling in chat call AdminReadScope.valueOf on each item, so one unknown name makes
-- the whole row unreadable, not the one item). V202611191003 did the same for PAGES one story earlier; it stays as
-- Flyway history.
--
-- A grant whose every scope is retired is deleted, not emptied. shared.AdminReadGrant refuses an empty item set --
-- "A grant covers at least one item" -- and SplitVersionGrants builds the record inside its row mapper over every
-- row of the admin and the Job, with no ends_at filter, so one such row would throw before any expiry or scope
-- check and take every other grant of that Administrator on that Job down with it. An Abuse Report's grant is
-- exactly that shape: AbuseReports.take pushes AdminReadItem.forAbuseReport of the single item the report names,
-- and until this story a report on a Work Item note named WORK_ITEM_NOTES. The clause deletes any row already empty
-- too, so the post-condition is absolute rather than resting on an argument about what could exist. A deleted grant
-- opens nothing, which is what it opened before: the kind of content it named is gone.
--
-- A Split Resolution's grant is the one this module serves (SPLIT_VERSIONS), and it was pushed down with the Crew
-- Chat and every party's Work Items beside it, so stripping the retired items leaves the split's versions and the
-- Crew Chat. A Dispute's and an escalated Team Issue's grants are stored here too, since every grant is pushed to
-- every holding module; they keep their Job Chat and Crew Chat. Each keeps its end: it still opens exactly what its
-- case may read, minus a kind of content that no longer exists. The audit trail of earlier reads stays (AD-8).
-- Personal data: rows deleted; nothing new is kept.

DELETE FROM job.admin_read_grant
WHERE items = '[]'::jsonb
   OR NOT EXISTS (SELECT 1 FROM jsonb_array_elements(items) AS item
                  WHERE item ->> 'scope' NOT IN ('WORK_ITEMS', 'WORK_ITEM_NOTES', 'ATTACHMENTS'));

UPDATE job.admin_read_grant
SET items = (SELECT jsonb_agg(item) FROM jsonb_array_elements(items) AS item
             WHERE item ->> 'scope' NOT IN ('WORK_ITEMS', 'WORK_ITEM_NOTES', 'ATTACHMENTS'))
WHERE items @> '[{"scope": "WORK_ITEMS"}]'
   OR items @> '[{"scope": "WORK_ITEM_NOTES"}]'
   OR items @> '[{"scope": "ATTACHMENTS"}]';
