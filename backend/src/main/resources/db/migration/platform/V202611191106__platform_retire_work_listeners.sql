-- Story 24.2b (D1, AD-2, AD-3, AD-9): the module vn.vibefix.work is deleted, so its four listeners
-- (WorkListeners.onAccountLocked, onCrewMemberRemoved, onJobTransitioned and onAgreementConfirmed) are gone and an
-- unfinished publication of AccountLocked, CrewMemberRemoved, JobTransitioned or AgreementConfirmed to one of them
-- could never complete. Those publications and their stalled marks go, as Story 20.4's did for team's lock listener
-- (V202610271301). Completed ones stay until the registry's purge, and every other listener keeps its own.
-- Personal data: rows deleted; nothing new is kept.

DELETE FROM platform.event_publication_stalled
WHERE publication_id IN (
    SELECT id FROM platform.event_publication
    WHERE completion_date IS NULL
      AND listener_id LIKE 'vn.vibefix.work.%');

DELETE FROM platform.event_publication
WHERE completion_date IS NULL
  AND listener_id LIKE 'vn.vibefix.work.%';
