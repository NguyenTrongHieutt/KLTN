-- Story 8.2 (FR-23, AD-33): a handover's commit whose message mentions a Work Item key of the Job's Board
-- (shared.WorkItemKey) is linked to that Work Item when the handover is recorded. A commit links each
-- Work Item once, whichever handover carried it first.
-- Personal data: none; a Product's deletion deletes the links with the Work Items (WorkPersonalData).

CREATE TABLE work.work_item_commit
(
    work_item_id UUID        NOT NULL REFERENCES work.work_item (id),
    sha          TEXT        NOT NULL CHECK (sha ~ '^[0-9a-f]{40}$'),
    job_id       UUID        NOT NULL,
    handover_id  UUID        NOT NULL,
    linked_at    TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (work_item_id, sha)
);

CREATE INDEX work_item_commit_job_idx ON work.work_item_commit (job_id);

GRANT SELECT, INSERT, DELETE ON work.work_item_commit TO ${runtime_role};
