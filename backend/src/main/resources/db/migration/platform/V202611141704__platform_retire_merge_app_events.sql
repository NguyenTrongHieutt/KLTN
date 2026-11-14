-- Story 23.9a (FR-37, R1, D7, AD-9): the Merge App's installation events no longer bind anything, so
-- repo's FinalMergeListeners.onWebhook is gone (the GitHubWebhookReceived event itself stays for its other
-- listeners). An unfinished publication to it could never complete. Those publications and their stalled marks go;
-- completed ones stay until the registry's purge, and the other listeners keep theirs. Its replay records go too.

DELETE FROM platform.event_publication_stalled
WHERE publication_id IN (
    SELECT id FROM platform.event_publication
    WHERE completion_date IS NULL AND listener_id LIKE '%.FinalMergeListeners.onWebhook(%'
);

DELETE FROM platform.event_publication
WHERE completion_date IS NULL AND listener_id LIKE '%.FinalMergeListeners.onWebhook(%';

DELETE FROM platform.processed_event WHERE listener = 'repo.FinalMergeListeners.onWebhook';
