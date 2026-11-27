-- Story 25.6 (FR-83, UX-DR131, AD-47): the split notices name who drafts the split, the Mentor or, in a Self-mentored
-- Job, the Customer, who drafts it and is no party. job.split_pending takes "who", inside its sentence ("người hướng dẫn"
-- or "khách hàng"); job.split_proposed and job.split_edited take "drafter", which begins theirs ("Người hướng dẫn" or
-- "Khách hàng"). The rows already stored are all of normal Jobs and gain the Mentor's word, so an inbox row keeps its
-- title. An email of these types still waiting to be sent keeps its own copy of the old parameters in operations, which
-- this schema does not write: it ends TEMPLATE_VALUE_MISSING, and its inbox row stands. No such email exists before
-- production.

UPDATE notification.notification
SET params = params || jsonb_build_object('who', 'người hướng dẫn')
WHERE type = 'job.split_pending'
  AND NOT jsonb_exists(params, 'who');

UPDATE notification.notification
SET params = params || jsonb_build_object('drafter', 'Người hướng dẫn')
WHERE type IN ('job.split_proposed', 'job.split_edited')
  AND NOT jsonb_exists(params, 'drafter');
