-- Story 24.2b (D1, AD-8, AD-20, FR-53, R39): the Board is retired, so AdminReadScope has no WORK_ITEMS,
-- WORK_ITEM_NOTES or ATTACHMENTS any more and a stored grant naming one could not be read back at all (ChatGrants
-- and its sibling in job call AdminReadScope.valueOf on each item, so one unknown name makes the whole row
-- unreadable, not the one item). V202611191002 did the same for PAGES one story earlier; it stays as Flyway history.
--
-- A Dispute's grant held the Job Chat beside all three, so stripping them leaves it with the Job Chat alone; an
-- escalated Team Issue's held the Crew Chat and the raiser's Work Items, so it keeps the Crew Chat; a Split
-- Resolution's held the split's versions, the Crew Chat and every party's Work Items, so it keeps the first two. A
-- grant that somehow held only retired scopes keeps an empty list rather than the NULL jsonb_agg would give a NOT
-- NULL column. The grant keeps its end: it still opens exactly what its case may read, minus a kind of content that
-- no longer exists. The audit trail of earlier reads stays (AD-8).
-- Personal data: nothing new is kept.

UPDATE chat.admin_read_grant
SET items = coalesce((SELECT jsonb_agg(item) FROM jsonb_array_elements(items) AS item
                      WHERE item ->> 'scope' NOT IN ('WORK_ITEMS', 'WORK_ITEM_NOTES', 'ATTACHMENTS')), '[]'::jsonb)
WHERE items @> '[{"scope": "WORK_ITEMS"}]'
   OR items @> '[{"scope": "WORK_ITEM_NOTES"}]'
   OR items @> '[{"scope": "ATTACHMENTS"}]';
