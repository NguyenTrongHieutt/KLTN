-- Story 24.1 (D2, AD-20, FR-53): the Documentation Space is retired, so AdminReadScope has no PAGES any more and a
-- stored grant naming it could not be read back at all (ChatGrants and its two siblings call
-- AdminReadScope.valueOf on each item). A Dispute's grant is the only one that ever held PAGES, beside the Job Chat,
-- Work Items, their notes and the Attachment list, so stripping that one item leaves every such grant with four; one
-- that somehow held PAGES alone keeps an empty list rather than the NULL jsonb_agg would give a NOT NULL column. The
-- grant keeps its end: it still opens exactly what its case may read, minus a kind of content that no longer exists.
-- The audit trail of earlier reads stays (AD-8).
-- Personal data: nothing new is kept.

UPDATE chat.admin_read_grant
SET items = coalesce((SELECT jsonb_agg(item) FROM jsonb_array_elements(items) AS item
                      WHERE item ->> 'scope' <> 'PAGES'), '[]'::jsonb)
WHERE items @> '[{"scope": "PAGES"}]';
