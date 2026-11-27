-- Story 25.6 (FR-83, UX-DR131, AD-47): job.split_pending, job.split_proposed and job.split_edited take the parameter
-- "who", the one who drafts the split: "người hướng dẫn" in a normal Job, "khách hàng" in a Self-mentored Job, where the
-- Customer drafts it and is no party. The rows already stored are all of normal Jobs and gain the Mentor's word, so an
-- inbox row keeps its title. An email of these types still waiting to be sent keeps its own copy of the old parameters
-- in operations, which this schema does not write: it ends TEMPLATE_VALUE_MISSING, and its inbox row stands. No such
-- email exists before production.

UPDATE notification.notification
SET params = params || jsonb_build_object('who', 'người hướng dẫn')
WHERE type IN ('job.split_pending', 'job.split_proposed', 'job.split_edited')
  AND NOT jsonb_exists(params, 'who');
