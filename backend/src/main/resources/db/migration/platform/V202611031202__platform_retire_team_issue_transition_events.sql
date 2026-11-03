-- Story 22.4 (AD-9, AD-40): a share Team Issue no longer exists to end as moot when its Milestone ends without a
-- release, so dispute's listener on JobTransitioned (TeamIssueListeners.onJobTransitioned) is gone, and an unfinished
-- publication to it could never complete. Those publications and their stalled marks go; completed ones stay until the
-- registry's purge, and the other listeners keep theirs.

DELETE FROM platform.event_publication_stalled
WHERE publication_id IN (
    SELECT id FROM platform.event_publication
    WHERE completion_date IS NULL
      AND listener_id LIKE '%.TeamIssueListeners.onJobTransitioned(%');

DELETE FROM platform.event_publication
WHERE completion_date IS NULL
  AND listener_id LIKE '%.TeamIssueListeners.onJobTransitioned(%';
