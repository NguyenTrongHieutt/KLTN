-- Story 25.5 (FR-32, UX-DR126, AD-47 exception 1): job.milestone_submitted takes the parameter "who" in place of
-- "mentor": "Người hướng dẫn {name}" in a normal Job, and "Thành viên {name}" in a Self-mentored Job, where a Crew
-- member hands over. The rows already stored are rewritten, so an inbox row keeps its title. An email of this type
-- still waiting to be sent keeps its own copy of the old parameters in operations, which this schema does not write:
-- it ends TEMPLATE_VALUE_MISSING, and its inbox row stands. No such email exists before production.

UPDATE notification.notification
SET params = (params - 'mentor') || jsonb_build_object('who', btrim('Người hướng dẫn ' || (params ->> 'mentor')))
WHERE type = 'job.milestone_submitted'
  AND jsonb_exists(params, 'mentor');
