-- Story 8.1 (FR-47, FR-66): when the Job reaches Closed or Cancelled, a message sent as written despite
-- the warning ("Vẫn gửi") is checked again and stored masked; the original is not kept.

ALTER TABLE chat.message
    ADD COLUMN redacted_at TIMESTAMPTZ;

GRANT UPDATE (body, masked_credentials, masked_personal, redacted_at) ON chat.message TO ${runtime_role};
