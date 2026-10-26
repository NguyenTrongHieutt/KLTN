-- Story 19.2 (AD-10): "sign in again" became "re-link GitHub", and the email template
-- identity.sign_in_again became identity.relink_github. Emails still queued under the old template would
-- abort with TEMPLATE_MISSING, so they take the new one, and ACC-2's address instead of /dang-nhap-lai.
-- (The notification rows are renamed by notification's own migration; this module owns the operations.)

UPDATE operations.external_operation
SET payload = jsonb_set(payload, '{template}', '"identity.relink_github"')
WHERE type = 'notification.email' AND status IN ('PENDING', 'RUNNING')
  AND payload ->> 'template' = 'identity.sign_in_again';

UPDATE operations.external_operation
SET payload = jsonb_set(payload, '{link}', '"/ho-so/xac-minh"')
WHERE type = 'notification.email' AND status IN ('PENDING', 'RUNNING')
  AND payload ->> 'template' = 'identity.relink_github' AND payload ->> 'link' = '/dang-nhap-lai';
