-- Story 24.2b (D1, AD-8, AD-20, FR-53, FR-84, R39): the Board is retired, so AdminReadScope has no WORK_ITEMS,
-- WORK_ITEM_NOTES or ATTACHMENTS any more and a stored grant naming one could not be read back at all
-- (SplitVersionGrants and its sibling in chat call AdminReadScope.valueOf on each item, so one unknown name makes
-- the whole row unreadable, not the one item). V202611191003 did the same for PAGES one story earlier; it stays as
-- Flyway history.
--
-- A Split Resolution's grant is the one this module serves (SPLIT_VERSIONS), and it was pushed down with the Crew
-- Chat and every party's Work Items beside it, so stripping the retired items leaves the split's versions and the
-- Crew Chat. A Dispute's and an escalated Team Issue's grants are stored here too, since every grant is pushed to
-- every holding module; they keep their Job Chat and Crew Chat. A grant that somehow held only retired scopes keeps
-- an empty list rather than the NULL jsonb_agg would give a NOT NULL column. The grant keeps its end: it still
-- opens exactly what its case may read, minus a kind of content that no longer exists. The audit trail of earlier
-- reads stays (AD-8).
-- Personal data: nothing new is kept.

UPDATE job.admin_read_grant
SET items = coalesce((SELECT jsonb_agg(item) FROM jsonb_array_elements(items) AS item
                      WHERE item ->> 'scope' NOT IN ('WORK_ITEMS', 'WORK_ITEM_NOTES', 'ATTACHMENTS')), '[]'::jsonb)
WHERE items @> '[{"scope": "WORK_ITEMS"}]'
   OR items @> '[{"scope": "WORK_ITEM_NOTES"}]'
   OR items @> '[{"scope": "ATTACHMENTS"}]';
