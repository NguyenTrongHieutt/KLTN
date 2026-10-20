-- The Crew Chat (FR-77, Story 4.9) and read-only Chats (FR-46, FR-77, AD-9): J4 opens the Crew Chat
-- beside the Job Chat, and J9 or J10 sets closed_at on both Chats of the Job once; a closed Chat is
-- read, never written. Every Job that already has a Job Chat gets its Crew Chat, opened with it.

ALTER TABLE chat.chat ADD COLUMN closed_at TIMESTAMPTZ;

GRANT UPDATE (closed_at) ON chat.chat TO ${runtime_role};

INSERT INTO chat.chat (id, job_id, product_id, kind, opened_at)
SELECT gen_random_uuid(), job_id, product_id, 'CREW', opened_at FROM chat.chat WHERE kind = 'JOB'
ON CONFLICT (job_id, kind) DO NOTHING;
