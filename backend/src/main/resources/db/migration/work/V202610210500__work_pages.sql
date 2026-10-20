-- The Documentation Space (FR-29, AD-21, AD-23): each Job with a Board keeps Markdown Pages in a tree
-- of at most two levels (work checks it under the Board lock), siblings in creation order (seq). Every
-- save writes a version (number, title, body, author, time), and a restore writes a new version from an
-- earlier one; the Page row carries the current version number that the next save must name. Versions
-- are never changed or deleted, except that erasing an account clears it as their author. The Mentor
-- and the Crew read Pages as job says (AD-41), an Administrator only under a PAGES grant (AD-20).
-- Personal data: a Product's deletion deletes its Pages (WorkPersonalData).

CREATE TABLE work.page
(
    id                    UUID PRIMARY KEY,
    seq                   BIGINT      NOT NULL GENERATED ALWAYS AS IDENTITY,
    job_id                UUID        NOT NULL REFERENCES work.board (job_id),
    parent_id             UUID REFERENCES work.page (id),
    title                 TEXT        NOT NULL CHECK (char_length(title) BETWEEN 1 AND 200),
    version_no            INTEGER     NOT NULL CHECK (version_no >= 1),
    created_by_account_id UUID,
    created_at            TIMESTAMPTZ NOT NULL,
    updated_at            TIMESTAMPTZ NOT NULL,
    CHECK (parent_id IS NULL OR parent_id <> id)
);

CREATE INDEX page_job_idx ON work.page (job_id, seq);

CREATE INDEX page_parent_idx ON work.page (parent_id) WHERE parent_id IS NOT NULL;

CREATE INDEX page_creator_idx ON work.page (created_by_account_id) WHERE created_by_account_id IS NOT NULL;

GRANT SELECT, INSERT, UPDATE, DELETE ON work.page TO ${runtime_role};

CREATE TABLE work.page_version
(
    page_id           UUID        NOT NULL REFERENCES work.page (id),
    version_no        INTEGER     NOT NULL CHECK (version_no >= 1),
    title             TEXT        NOT NULL CHECK (char_length(title) BETWEEN 1 AND 200),
    body              TEXT        NOT NULL CHECK (char_length(body) <= 100000),
    author_account_id UUID,
    restored_from     INTEGER CHECK (restored_from >= 1),
    created_at        TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (page_id, version_no)
);

CREATE INDEX page_version_author_idx ON work.page_version (author_account_id) WHERE author_account_id IS NOT NULL;

GRANT SELECT, INSERT, DELETE ON work.page_version TO ${runtime_role};

GRANT UPDATE (author_account_id) ON work.page_version TO ${runtime_role};
