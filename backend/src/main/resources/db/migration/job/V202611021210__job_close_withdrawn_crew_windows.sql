-- Story 21.5 review: V202610291800 released legacy ASSIGNED rows as WITHDRAWN without ending a window one of them had,
-- as V202610271300 ended theirs, so an activated row kept an open window. Personal data: unchanged.

UPDATE job.crew_row
SET window_end = coalesce(window_end, released_at)
WHERE status = 'RELEASED'
  AND released_reason = 'WITHDRAWN'
  AND activated_at IS NOT NULL
  AND window_end IS NULL;
