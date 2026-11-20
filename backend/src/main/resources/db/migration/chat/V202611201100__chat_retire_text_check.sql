-- Story 24.4a (D5, FR-46, FR-77, AD-18, NFR-10): FR-47 is removed, so nothing checks, masks or refuses a chat message
-- for what it says any more. A message is stored as written, and J9 or J10 only makes both Chats read-only: no
-- message is rewritten at the Job's end, and the Chats stay until the retention run. The runtime role's column UPDATE
-- on chat.message existed only for that rewrite (V202610221810), so it is revoked, and the four columns of the check
-- and the rewrite go: masked_credentials and masked_personal counted what "Che và gửi" masked, sent_despite_warning
-- marked "Vẫn gửi", and redacted_at marked a message the Job's end had checked again.
--
-- Message bodies are untouched. A body stored masked before this migration stays masked; nothing kept the original.
-- The table keeps SELECT, INSERT and DELETE for the runtime role, as V202610201800 granted them.
-- Personal data: nothing new is kept; four columns about how a message was checked are dropped, and every body stays.

REVOKE UPDATE (body, masked_credentials, masked_personal, redacted_at) ON chat.message FROM ${runtime_role};

ALTER TABLE chat.message
    DROP COLUMN masked_credentials,
    DROP COLUMN masked_personal,
    DROP COLUMN sent_despite_warning,
    DROP COLUMN redacted_at;
