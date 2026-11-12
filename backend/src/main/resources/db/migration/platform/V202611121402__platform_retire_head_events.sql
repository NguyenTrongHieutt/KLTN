-- Story 23.2 (D9, R16, AD-9): vibefix no longer tracks Working heads, so the event WorkingHeadMoved is gone, and so
-- are its listener (delivery's HandoverVoids.onWorkingHeadMoved) and repo's push-webhook listener
-- (PushScans.onWebhook; the webhook event itself stays for its other listeners). An unfinished publication to any of
-- them could never complete. Those publications and their stalled marks go; completed ones stay until the registry's
-- purge, and the other listeners keep theirs. The replay records of both removed listeners go too.

DELETE FROM platform.event_publication_stalled
WHERE publication_id IN (
    SELECT id FROM platform.event_publication
    WHERE completion_date IS NULL
      AND (event_type = 'vn.vibefix.repo.WorkingHeadMoved'
           OR listener_id LIKE '%.HandoverVoids.onWorkingHeadMoved(%'
           OR listener_id LIKE '%.PushScans.onWebhook(%'));

DELETE FROM platform.event_publication
WHERE completion_date IS NULL
  AND (event_type = 'vn.vibefix.repo.WorkingHeadMoved'
       OR listener_id LIKE '%.HandoverVoids.onWorkingHeadMoved(%'
       OR listener_id LIKE '%.PushScans.onWebhook(%');

DELETE FROM platform.processed_event
WHERE listener IN ('repo.PushScans.onWebhook', 'delivery.HandoverVoids.onWorkingHeadMoved');
