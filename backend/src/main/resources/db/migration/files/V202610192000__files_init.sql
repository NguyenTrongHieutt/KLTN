-- Schema files: stored files (AD-21). Owned by the migration role; the runtime role ${runtime_role}
-- gets DML table by table. The bytes live on the files volume, never in PostgreSQL.

CREATE SCHEMA files;

GRANT USAGE ON SCHEMA files TO ${runtime_role};

-- One stored file. The purpose names the owning module's read policy (AD-20); account_id is the
-- uploader, whose hourly quota the row counts against (AD-34). A deleted file keeps its row, without
-- bytes, so the owner can tell that the deletion happened. Personal data: exported and erased (AD-24).
CREATE TABLE files.stored_file
(
    id         UUID PRIMARY KEY,
    purpose    TEXT        NOT NULL CHECK (purpose ~ '^[a-z]+\.[a-z0-9_]+$'),
    account_id UUID        NOT NULL,
    file_name  TEXT        NOT NULL CHECK (char_length(file_name) BETWEEN 1 AND 200),
    media_type TEXT        NOT NULL CHECK (char_length(media_type) BETWEEN 3 AND 100),
    kind       TEXT        NOT NULL CHECK (kind IN ('IMAGE', 'PDF', 'TEXT', 'ARCHIVE')),
    size_bytes BIGINT      NOT NULL CHECK (size_bytes BETWEEN 1 AND 10485760),
    sha256     TEXT        NOT NULL CHECK (sha256 ~ '^[0-9a-f]{64}$'),
    stored_at  TIMESTAMPTZ NOT NULL,
    deleted_at TIMESTAMPTZ
);

CREATE INDEX stored_file_account ON files.stored_file (account_id, stored_at);

GRANT SELECT, INSERT, UPDATE, DELETE ON files.stored_file TO ${runtime_role};
