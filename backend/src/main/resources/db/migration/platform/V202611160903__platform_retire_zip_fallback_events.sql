-- Story 23.10c (FR-38, D10, AD-9, R16): vn.vibefix.repo.FinalMergeAuthorizationEnded and its only listener,
-- repo.ZipFallbacks.onAuthorizationEnded, are deleted with the ZIP Fallback. AD-9 republishes outstanding events
-- at the next start (spring.modulith.events.republish-outstanding-events-on-restart), and a publication whose
-- event class no longer exists cannot be deserialized at all, so every incomplete one goes with its stalled mark.
--
-- This answers deferred-work's entry from Story 23.10b: an undelivered publication for a row that ended DEADLINE in
-- the seconds between end(id, "DEADLINE") and ZipFallbacks.start would now reach no handler, leaving that Job in
-- CLOSING. Deleting the publication settles it the honest way: the row's Job is ended by FinalMergeDeadline itself,
-- which applies J9 before it ends the row (Story 23.10b), so nothing is left for a listener to do. The same holds
-- for the publications of rows that ended ZIP_FALLBACK, CANCELLED or PROPOSAL_ENDED.
--
-- A completed publication waits for the registry's purge, as always, and no other listener's rows are touched.
-- Personal data: a publication's payload holds Job and row ids, which go with the row; nothing new is kept.

DELETE FROM platform.event_publication_stalled
WHERE publication_id IN (
    SELECT id FROM platform.event_publication
    WHERE completion_date IS NULL
      AND event_type = 'vn.vibefix.repo.FinalMergeAuthorizationEnded'
);

DELETE FROM platform.event_publication
WHERE completion_date IS NULL
  AND event_type = 'vn.vibefix.repo.FinalMergeAuthorizationEnded';

DELETE FROM platform.processed_event WHERE listener = 'repo.ZipFallbacks.onAuthorizationEnded';
