-- Schema repo (FR-32, AD-11, AD-44, AD-46, Story 23.8a): one row per handover copy on the runner. The Mentor's
-- "Gửi bàn giao" keeps a pending submission in delivery and asks repo to copy; this row is the External Operation's
-- state, under the Job's busy marker: the exchange correlation of the run dispatched last, what that run was last
-- read as, the pull request opened once the branch read back, and how the copy ended. Nothing here holds code or a
-- changed path: the run's path list lives in github.exchange_report and reaches delivery through repo's result.
-- Personal data: none; Job and submission IDs, repository names, commits and run states identify nobody.

CREATE TABLE repo.handover_copy
(
    operation_key   TEXT        PRIMARY KEY,
    submission_id   UUID        NOT NULL UNIQUE,
    job_id          UUID        NOT NULL,
    from_sha        TEXT        NOT NULL CHECK (from_sha ~ '^[0-9a-f]{40}$'),
    handover_commit TEXT        NOT NULL CHECK (handover_commit ~ '^[0-9a-f]{40}$'),
    pull_title      TEXT        NOT NULL CHECK (char_length(pull_title) BETWEEN 1 AND 300),
    pull_body       TEXT        NOT NULL CHECK (char_length(pull_body) BETWEEN 1 AND 2000),
    status          TEXT        NOT NULL CHECK (status IN ('RUNNING', 'DONE', 'REFUSED')),
    refusal         TEXT CHECK (refusal IN ('WORKFLOW_FILES_CHANGED', 'RUN_FAILED', 'EXCHANGE_REFUSED',
                                            'PATH_LIST_UNREADABLE')),
    correlation_id  UUID,
    dispatched_at   TIMESTAMPTZ,
    run_status      TEXT CHECK (run_status IN ('QUEUED', 'IN_PROGRESS', 'COMPLETED')),
    run_conclusion  TEXT CHECK (run_conclusion ~ '^[a-z_]{1,40}$'),
    pull_number     INTEGER CHECK (pull_number > 0),
    requested_at    TIMESTAMPTZ NOT NULL,
    finished_at     TIMESTAMPTZ,
    CHECK (from_sha <> handover_commit),
    CHECK ((status = 'REFUSED') = (refusal IS NOT NULL)),
    CHECK ((status = 'RUNNING') = (finished_at IS NULL)),
    CHECK (status <> 'DONE' OR pull_number IS NOT NULL),
    CHECK ((correlation_id IS NULL) = (dispatched_at IS NULL))
);

CREATE INDEX handover_copy_job_idx ON repo.handover_copy (job_id);

GRANT SELECT, INSERT, UPDATE, DELETE ON repo.handover_copy TO ${runtime_role};
