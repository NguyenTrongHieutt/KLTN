-- Story 22.4 (AD-20, SG-63): the Administrator read grants no longer name the Crew Terms. A stored grant with that item
-- loses it, as AdminReadScope no longer reads it; the Crew Chat stays in every such grant, so none is left empty. Such a
-- grant can no longer tell a retired share Team Issue's from a Rating's, so it ends now: for an open case to be read on,
-- another Administrator takes it over. The audit trail of earlier reads stays (AD-8).
-- Personal data: nothing new is kept.

UPDATE chat.admin_read_grant
SET items   = (SELECT jsonb_agg(item) FROM jsonb_array_elements(items) AS item WHERE item ->> 'scope' <> 'CREW_TERMS'),
    ends_at = coalesce(ends_at, now())
WHERE items @> '[{"scope": "CREW_TERMS"}]';
