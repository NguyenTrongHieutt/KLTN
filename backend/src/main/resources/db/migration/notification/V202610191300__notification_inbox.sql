-- In-app notifications and to-do items (AD-27, FR-28, FR-48). Rows hold internal IDs, the type or
-- to-do key, the parameters the text needs, and the platform path the item opens. They are deleted
-- 90 days after they were created (notifications) or closed (to-dos), and on account erasure (AD-24).

-- One row per recipient of a notification; a repeated send of the same cause adds nothing.
CREATE TABLE notification.notification
(
    id                UUID PRIMARY KEY,
    account_id        UUID        NOT NULL,
    type              TEXT        NOT NULL CHECK (type ~ '^[a-z]+(\.[a-z0-9_]+)+$'),
    causing_record_id UUID        NOT NULL,
    params            JSONB       NOT NULL,
    link              TEXT        NOT NULL CHECK (link ~ '^/[a-z0-9/-]*$'),
    job_id            UUID,
    created_at        TIMESTAMPTZ NOT NULL,
    read_at           TIMESTAMPTZ,
    UNIQUE (type, causing_record_id, account_id)
);

CREATE INDEX notification_account_idx ON notification.notification (account_id, created_at DESC);
CREATE INDEX notification_unread_idx ON notification.notification (account_id) WHERE read_at IS NULL;

GRANT SELECT, INSERT, UPDATE, DELETE ON notification.notification TO ${runtime_role};

-- An expected action of one recipient about one subject record, opened and closed by the module that
-- owns it; at most one is open per key, recipient and subject. Users never delete them.
CREATE TABLE notification.todo
(
    id          UUID PRIMARY KEY,
    todo_key    TEXT        NOT NULL CHECK (todo_key ~ '^[a-z]+(\.[a-z0-9_]+)+$'),
    account_id  UUID        NOT NULL,
    subject_id  UUID        NOT NULL,
    tier        SMALLINT    NOT NULL CHECK (tier IN (1, 2, 3)),
    params      JSONB       NOT NULL,
    link        TEXT        NOT NULL CHECK (link ~ '^/[a-z0-9/-]*$'),
    deadline_at TIMESTAMPTZ,
    job_id      UUID,
    opened_at   TIMESTAMPTZ NOT NULL,
    closed_at   TIMESTAMPTZ
);

CREATE UNIQUE INDEX todo_open_idx ON notification.todo (todo_key, account_id, subject_id) WHERE closed_at IS NULL;
CREATE INDEX todo_account_open_idx ON notification.todo (account_id, tier) WHERE closed_at IS NULL;

GRANT SELECT, INSERT, UPDATE, DELETE ON notification.todo TO ${runtime_role};
