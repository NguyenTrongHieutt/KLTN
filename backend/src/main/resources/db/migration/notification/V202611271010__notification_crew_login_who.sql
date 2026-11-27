-- Story 25.4 (AD-47 exception 12 c): job.crew_login_added and job.crew_login_removed take the parameter "who" in place
-- of "login": "@login" in a normal Job, and "name (@login)" for the Customer of a Self-mentored Job. The rows already
-- stored are rewritten, so an inbox row keeps its title. An email of these types still waiting to be sent keeps its
-- own copy of the old parameters in operations, which this schema does not write: it ends TEMPLATE_VALUE_MISSING, and
-- its inbox row stands. No such email exists before production.

UPDATE notification.notification
SET params = (params - 'login') || jsonb_build_object('who', '@' || (params ->> 'login'))
WHERE type IN ('job.crew_login_added', 'job.crew_login_removed')
  AND jsonb_exists(params, 'login');
