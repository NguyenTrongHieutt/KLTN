-- Story 8.2 (FR-23, FR-32, AD-11, AD-33): a Code Job's handover pins its Handover Commit (the Working
-- default branch's head the preflight read) on handover/{id} in the Intermediary Repository with the
-- System's pull request. It keeps the range it covers (from the previous Handover Commit of the Job, or
-- the commit Setup copied), each commit with the first line of its message, its author's GitHub numeric
-- ID (none when GitHub resolved no account: unattributed) and the Work Item keys it linked, the range's
-- Sensitive Changes, and the Pages updated since the range started.
-- Personal data: the authors' GitHub IDs identify accounts on GitHub, not vibefix accounts; they stay with
-- the handover for Portfolio facts and go with the Product (DeliveryPersonalData).

ALTER TABLE delivery.handover
    ADD COLUMN handover_commit      TEXT CHECK (handover_commit ~ '^[0-9a-f]{40}$'),
    ADD COLUMN range_start          TEXT CHECK (range_start ~ '^[0-9a-f]{40}$'),
    ADD COLUMN pull_number          INTEGER CHECK (pull_number > 0),
    ADD COLUMN unattributed_commits INTEGER NOT NULL DEFAULT 0 CHECK (unattributed_commits >= 0),
    ADD COLUMN commits_truncated    BOOLEAN NOT NULL DEFAULT FALSE,
    ADD CONSTRAINT handover_code_check CHECK ((handover_commit IS NULL) = (range_start IS NULL)
        AND (handover_commit IS NULL) = (pull_number IS NULL));

CREATE TABLE delivery.handover_commit
(
    handover_id      UUID    NOT NULL REFERENCES delivery.handover (id),
    position         INTEGER NOT NULL CHECK (position >= 0),
    sha              TEXT    NOT NULL CHECK (sha ~ '^[0-9a-f]{40}$'),
    message          TEXT    NOT NULL CHECK (char_length(message) <= 300),
    author_github_id BIGINT,
    work_item_keys   TEXT[]  NOT NULL DEFAULT '{}',
    PRIMARY KEY (handover_id, position)
);

GRANT SELECT, INSERT, DELETE ON delivery.handover_commit TO ${runtime_role};

CREATE TABLE delivery.handover_sensitive_change
(
    handover_id UUID    NOT NULL REFERENCES delivery.handover (id),
    position    INTEGER NOT NULL CHECK (position >= 0),
    path        TEXT    NOT NULL CHECK (char_length(path) BETWEEN 1 AND 1000),
    kind        TEXT    NOT NULL CHECK (kind IN ('CI_CONFIG', 'DEPENDENCIES', 'BUILD_SCRIPT', 'BINARY')),
    PRIMARY KEY (handover_id, position)
);

GRANT SELECT, INSERT, DELETE ON delivery.handover_sensitive_change TO ${runtime_role};

CREATE TABLE delivery.handover_page
(
    handover_id      UUID    NOT NULL REFERENCES delivery.handover (id),
    page_id          UUID    NOT NULL,
    title            TEXT    NOT NULL CHECK (char_length(title) BETWEEN 1 AND 200),
    version_no       INTEGER NOT NULL CHECK (version_no >= 1),
    customer_visible BOOLEAN NOT NULL,
    PRIMARY KEY (handover_id, page_id)
);

GRANT SELECT, INSERT, DELETE ON delivery.handover_page TO ${runtime_role};
