-- Story 23.1 (D8, R16, AD-9): vibefix scans no code, so the event ScanCompleted is gone, and so are its listeners
-- (product's CleanRepositories.on and repo's PushScans.onScanCompleted) and scanning's own listener on
-- OperationCompleted (ScanOperationHandler.on). An unfinished publication to any of them could never complete. Those
-- publications and their stalled marks go; completed ones stay until the registry's purge, and the other listeners
-- keep theirs. The replay records of the push-scan listener go too.

DELETE FROM platform.event_publication_stalled
WHERE publication_id IN (
    SELECT id FROM platform.event_publication
    WHERE completion_date IS NULL
      AND (event_type = 'vn.vibefix.scanning.ScanCompleted'
           OR listener_id LIKE '%.CleanRepositories.on(%'
           OR listener_id LIKE '%.PushScans.onScanCompleted(%'
           OR listener_id LIKE '%.ScanOperationHandler.on(%'));

DELETE FROM platform.event_publication
WHERE completion_date IS NULL
  AND (event_type = 'vn.vibefix.scanning.ScanCompleted'
       OR listener_id LIKE '%.CleanRepositories.on(%'
       OR listener_id LIKE '%.PushScans.onScanCompleted(%'
       OR listener_id LIKE '%.ScanOperationHandler.on(%');

DELETE FROM platform.processed_event WHERE listener = 'repo.PushScans.onScanCompleted';
