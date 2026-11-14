-- Story 23.9b (R1, R16, AD-9, AD-10): App.MERGE is gone from the enum, so a serialized event that names it can no
-- longer be read back. Story 23.9a's V202611141704 deleted the publications of the removed
-- FinalMergeListeners.onWebhook only; the listeners that stay (repo's GrantChecks.onWebhook and
-- PlatformGuard.onWebhook, admin's AdminAlerts.onConfigurationDrift) may still hold unfinished publications whose
-- serialized_event carries "app":"MERGE", and AD-9 republishes those at the next start
-- (spring.modulith.events.republish-outstanding-events-on-restart). Deserializing one would ask for an App constant
-- that no longer exists, so they go with their stalled marks. A publication of either event for another App stays,
-- completed ones stay until the registry's purge, and no replay record names an App.
-- Personal data: none; a publication's payload holds GitHub numeric IDs, which go with the row.

DELETE FROM platform.event_publication_stalled
WHERE publication_id IN (
    SELECT id FROM platform.event_publication
    WHERE completion_date IS NULL
      AND event_type IN ('vn.vibefix.github.GitHubWebhookReceived', 'vn.vibefix.github.GitHubConfigurationDrift')
      AND serialized_event LIKE '%"app":"MERGE"%'
);

DELETE FROM platform.event_publication
WHERE completion_date IS NULL
  AND event_type IN ('vn.vibefix.github.GitHubWebhookReceived', 'vn.vibefix.github.GitHubConfigurationDrift')
  AND serialized_event LIKE '%"app":"MERGE"%';
