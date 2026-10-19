-- Schema chat (FR-46, FR-47, AD-2): the Job Chat between a Job's Customer and its Mentor, opened at
-- J4, and later the Crew Chat (Story 4.9), one of each per Job. A message is plain text of at most
-- 4000 characters with an optional attachment kept by files; after the FR-47 warning it holds either
-- the masked text (masked_* count what was masked) or the text as written (sent_despite_warning).
-- Read markers drive the unread dot. Administrators read a Chat only under a grant a case module
-- pushed down (AD-20), each read audited. Only the participants read a Chat (AD-20).
-- Personal data: messages and markers are exported for their author or reader and deleted on
-- erasure; a Product's deletion deletes its Chats (ChatPersonalData).

CREATE SCHEMA chat;

GRANT USAGE ON SCHEMA chat TO ${runtime_role};

CREATE TABLE chat.chat
(
    id         UUID PRIMARY KEY,
    job_id     UUID        NOT NULL,
    product_id UUID        NOT NULL,
    kind       TEXT        NOT NULL CHECK (kind IN ('JOB', 'CREW')),
    opened_at  TIMESTAMPTZ NOT NULL,
    UNIQUE (job_id, kind)
);

CREATE INDEX chat_product_idx ON chat.chat (product_id);

GRANT SELECT, INSERT, DELETE ON chat.chat TO ${runtime_role};

CREATE TABLE chat.message
(
    id                    UUID PRIMARY KEY,
    chat_id               UUID        NOT NULL REFERENCES chat.chat (id),
    author_account_id     UUID        NOT NULL,
    body                  TEXT        NOT NULL CHECK (char_length(body) <= 4000),
    masked_credentials    INTEGER     NOT NULL DEFAULT 0 CHECK (masked_credentials >= 0),
    masked_personal       INTEGER     NOT NULL DEFAULT 0 CHECK (masked_personal >= 0),
    sent_despite_warning  BOOLEAN     NOT NULL DEFAULT FALSE,
    attachment_file_id    UUID,
    attachment_name       TEXT CHECK (char_length(attachment_name) BETWEEN 1 AND 255),
    attachment_media_type TEXT,
    attachment_size       BIGINT CHECK (attachment_size > 0),
    sent_at               TIMESTAMPTZ NOT NULL,
    CHECK (char_length(body) > 0 OR attachment_file_id IS NOT NULL),
    CHECK ((attachment_file_id IS NULL) = (attachment_name IS NULL))
);

CREATE INDEX message_chat_idx ON chat.message (chat_id, sent_at DESC, id DESC);

CREATE INDEX message_author_idx ON chat.message (author_account_id);

CREATE UNIQUE INDEX message_attachment_idx ON chat.message (attachment_file_id) WHERE attachment_file_id IS NOT NULL;

GRANT SELECT, INSERT, DELETE ON chat.message TO ${runtime_role};

CREATE TABLE chat.read_marker
(
    chat_id    UUID        NOT NULL REFERENCES chat.chat (id),
    account_id UUID        NOT NULL,
    read_at    TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (chat_id, account_id)
);

CREATE INDEX read_marker_account_idx ON chat.read_marker (account_id);

GRANT SELECT, INSERT, UPDATE, DELETE ON chat.read_marker TO ${runtime_role};

CREATE TABLE chat.admin_read_grant
(
    grant_id         UUID PRIMARY KEY,
    admin_account_id UUID  NOT NULL,
    job_id           UUID  NOT NULL,
    items            JSONB NOT NULL,
    reason_id        UUID  NOT NULL,
    ends_at          TIMESTAMPTZ
);

CREATE INDEX admin_read_grant_job_idx ON chat.admin_read_grant (job_id, admin_account_id);

GRANT SELECT, INSERT, UPDATE, DELETE ON chat.admin_read_grant TO ${runtime_role};
