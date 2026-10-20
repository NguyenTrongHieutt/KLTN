-- Attachments and notes on Work Items (FR-76, AD-21, AD-34, AD-39): an Attachment is a file, which files
-- stores under the purpose work.attachment and which counts toward the Job's 200 MB, or an https link
-- with an optional label, each with its author and time. Removing one marks it removed with the person
-- and the time; the row and the file stay until the Job's data is deleted (NFR-10). A note is a history
-- entry (NOTE) whose comment is the text; a correction names the earlier note it quotes. Adding and
-- removing an Attachment write ATTACHED and DETACHED entries that name it, so notes, Attachments, and
-- reviews read as one history in seq order. No row's content is ever updated: the runtime role may only
-- mark an Attachment removed and clear an erased account.
-- Personal data: exported for their author, the author cleared on erasure, deleted with the Product
-- (WorkPersonalData).

CREATE TABLE work.attachment
(
    id                UUID PRIMARY KEY,
    seq               BIGINT      NOT NULL GENERATED ALWAYS AS IDENTITY,
    work_item_id      UUID        NOT NULL REFERENCES work.work_item (id),
    job_id            UUID        NOT NULL,
    kind              TEXT        NOT NULL CHECK (kind IN ('FILE', 'LINK')),
    file_id           UUID UNIQUE,
    file_name         TEXT CHECK (char_length(file_name) BETWEEN 1 AND 255),
    media_type        TEXT,
    size_bytes        BIGINT CHECK (size_bytes >= 0),
    url               TEXT CHECK (char_length(url) BETWEEN 9 AND 2000),
    label             TEXT CHECK (char_length(label) <= 200),
    author_account_id UUID,
    added_at          TIMESTAMPTZ NOT NULL,
    removed_at        TIMESTAMPTZ,
    removed_by        UUID,
    CHECK (
        (kind = 'FILE' AND file_id IS NOT NULL AND file_name IS NOT NULL AND media_type IS NOT NULL
            AND size_bytes IS NOT NULL AND url IS NULL AND label IS NULL)
        OR (kind = 'LINK' AND url IS NOT NULL AND file_id IS NULL AND file_name IS NULL AND media_type IS NULL
            AND size_bytes IS NULL)),
    CHECK (removed_by IS NULL OR removed_at IS NOT NULL)
);

CREATE INDEX attachment_item_idx ON work.attachment (work_item_id, seq);

CREATE INDEX attachment_job_idx ON work.attachment (job_id);

CREATE INDEX attachment_author_idx ON work.attachment (author_account_id) WHERE author_account_id IS NOT NULL;

GRANT SELECT, INSERT, DELETE ON work.attachment TO ${runtime_role};

GRANT UPDATE (removed_at, removed_by, author_account_id) ON work.attachment TO ${runtime_role};

ALTER TABLE work.work_item_history
    ADD COLUMN attachment_id UUID REFERENCES work.attachment (id),
    ADD COLUMN quotes_id     UUID REFERENCES work.work_item_history (id);

ALTER TABLE work.work_item_history DROP CONSTRAINT work_item_history_action_check;

ALTER TABLE work.work_item_history
    ADD CONSTRAINT work_item_history_action_check
        CHECK (action IN ('SEEDED', 'CREATED', 'UPDATED', 'MOVED', 'SUBMITTED', 'APPROVED', 'RETURNED', 'NOTE',
                          'ATTACHED', 'DETACHED')),
    ADD CONSTRAINT work_item_history_note_check CHECK (action <> 'NOTE' OR char_length(comment) >= 1),
    ADD CONSTRAINT work_item_history_attachment_check
        CHECK ((action IN ('ATTACHED', 'DETACHED')) = (attachment_id IS NOT NULL)),
    ADD CONSTRAINT work_item_history_quote_check CHECK (quotes_id IS NULL OR action = 'NOTE');

CREATE INDEX work_item_history_attachment_idx ON work.work_item_history (attachment_id) WHERE attachment_id IS NOT NULL;
