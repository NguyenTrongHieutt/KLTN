-- Story 20.4 (AD-9, AD-19): a lock no longer acts on Teams, so team's lock listener
-- (TeamLifecycle.onAccountLocked) is gone, and an unfinished publication of AccountLocked to it could never
-- complete. Those publications and their stalled marks go; completed ones stay until the registry's purge, and the
-- other lock listeners keep theirs.

DELETE FROM platform.event_publication_stalled
WHERE publication_id IN (
    SELECT id FROM platform.event_publication
    WHERE completion_date IS NULL
      AND listener_id LIKE '%.TeamLifecycle.onAccountLocked(%');

DELETE FROM platform.event_publication
WHERE completion_date IS NULL
  AND listener_id LIKE '%.TeamLifecycle.onAccountLocked(%';
