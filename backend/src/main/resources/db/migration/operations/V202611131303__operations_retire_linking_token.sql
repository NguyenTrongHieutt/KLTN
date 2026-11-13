-- Story 23.5d (R31, AD-6, AD-10, AD-27, SG-9): vibefix keeps no GitHub linking token, and every revocation it asks
-- for now revokes the whole OAuth grant.
-- Personal data: the queued emails are deleted; the revocations keep the sealed token they carry until they
-- complete; nothing new is kept.

-- The identity.relink_github email template is gone with its notification. An email still queued under it, or a
-- failed one an Administrator could retry, would abort with TEMPLATE_MISSING, so it goes, with the personal data it
-- holds.
DELETE FROM operations.external_operation
WHERE type = 'notification.email' AND status IN ('PENDING', 'RUNNING', 'FAILED')
  AND payload ->> 'template' = 'identity.relink_github';

-- A revocation queued before this story with "revoke": "TOKEN" would revoke that token alone and leave vibefix's
-- grant on the person's GitHub account. Without the key it revokes the whole grant. The guard trigger refuses any
-- change to a payload, so each such row is re-inserted unchanged but for its payload, as Story 23.1 moved its Setup
-- operations. A failed one holds no token any more (its personal data was cleared), so its retry ends as before.
WITH tokens AS (
    DELETE FROM operations.external_operation
    WHERE type = 'identity.revoke_oauth_grant' AND status IN ('PENDING', 'RUNNING', 'FAILED')
      AND payload ->> 'revoke' IS NOT NULL
    RETURNING *
)
INSERT INTO operations.external_operation (id, operation_key, type, lane, priority, status, payload, personal_data,
                                           account_id, attempt_count, next_attempt_at, lease_until, read_back_first,
                                           result, error_code, created_at, completed_at)
SELECT id, operation_key, type, lane, priority, status, payload - 'revoke', personal_data, account_id, attempt_count,
       next_attempt_at, lease_until, read_back_first, result, error_code, created_at, completed_at
FROM tokens;
