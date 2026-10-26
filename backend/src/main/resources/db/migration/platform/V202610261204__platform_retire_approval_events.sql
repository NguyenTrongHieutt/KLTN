-- Story 19.7 (AD-9): the events MentorApproved and MentorApprovalRevoked are gone, and so are their
-- listeners (team's TeamLifecycle and matching's MatchingListeners), so an unfinished publication of
-- them could never complete. Those publications and their stalled marks go; completed ones stay until
-- the registry's purge.

DELETE FROM platform.event_publication_stalled
WHERE publication_id IN (
    SELECT id FROM platform.event_publication
    WHERE completion_date IS NULL
      AND (event_type IN ('vn.vibefix.identity.MentorApproved', 'vn.vibefix.identity.MentorApprovalRevoked')
           OR listener_id LIKE '%.onMentorApproved(%' OR listener_id LIKE '%.onMentorApprovalRevoked(%'));

DELETE FROM platform.event_publication
WHERE completion_date IS NULL
  AND (event_type IN ('vn.vibefix.identity.MentorApproved', 'vn.vibefix.identity.MentorApprovalRevoked')
       OR listener_id LIKE '%.onMentorApproved(%' OR listener_id LIKE '%.onMentorApprovalRevoked(%');
