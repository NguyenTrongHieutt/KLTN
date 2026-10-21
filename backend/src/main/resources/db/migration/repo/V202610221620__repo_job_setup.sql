-- Story 7.2 (FR-18, AD-5, AD-11, AD-28): J7's Setup stage. Each attempt of a Code Job's Setup is one row:
-- RUNNING under the Job's busy marker, then DONE, BLOCKED with a plain reason the Customer can fix (they
-- may start a new attempt), or FAILED when the operation ran out of retries (an Administrator retries it).
-- Its three steps record when each finished: the Source Repository checked again and the commit the J7
-- scan approved, the Intermediary Repository copied at that commit, and the Working Repository forked
-- with the rulesets read back. At most one attempt per Job is RUNNING or DONE.
-- The repository names are full names (organization/name).
-- Personal data: none. The Job's Product and the platform organization's repository names only.

CREATE TABLE repo.job_setup
(
    id                     UUID PRIMARY KEY,
    job_id                 UUID        NOT NULL,
    product_id             UUID        NOT NULL,
    status                 TEXT        NOT NULL CHECK (status IN ('RUNNING', 'BLOCKED', 'FAILED', 'DONE')),
    blocked_reason         TEXT CHECK (blocked_reason IN ('INTAKE_FINDING_OPEN', 'SOURCE_NOT_PUBLIC', 'NO_ADMIN_PERMISSION',
        'SIGN_IN_AGAIN')),
    open_findings          INTEGER CHECK (open_findings > 0),
    scan_run_id            UUID,
    source_full_name       TEXT CHECK (length(source_full_name) BETWEEN 3 AND 140),
    default_branch         TEXT CHECK (default_branch ~ '^[A-Za-z0-9._/-]{1,255}$'),
    approved_sha           TEXT CHECK (approved_sha ~ '^[0-9a-f]{40}$'),
    intermediary_github_id BIGINT,
    intermediary_name      TEXT CHECK (length(intermediary_name) BETWEEN 3 AND 140),
    working_github_id      BIGINT,
    working_name           TEXT CHECK (length(working_name) BETWEEN 3 AND 140),
    checked_at             TIMESTAMPTZ,
    intermediary_at        TIMESTAMPTZ,
    working_at             TIMESTAMPTZ,
    started_at             TIMESTAMPTZ NOT NULL,
    finished_at            TIMESTAMPTZ,
    CHECK ((status = 'BLOCKED') = (blocked_reason IS NOT NULL)),
    CHECK ((blocked_reason = 'INTAKE_FINDING_OPEN') = (open_findings IS NOT NULL)),
    CHECK ((status = 'RUNNING') = (finished_at IS NULL)),
    CHECK (checked_at IS NULL OR approved_sha IS NOT NULL),
    CHECK (intermediary_at IS NULL OR (checked_at IS NOT NULL AND intermediary_github_id IS NOT NULL)),
    CHECK (working_at IS NULL OR (intermediary_at IS NOT NULL AND working_github_id IS NOT NULL)),
    CHECK (status <> 'DONE' OR working_at IS NOT NULL)
);

CREATE UNIQUE INDEX job_setup_live_idx ON repo.job_setup (job_id) WHERE status IN ('RUNNING', 'DONE');

CREATE INDEX job_setup_job_idx ON repo.job_setup (job_id, started_at);

CREATE INDEX job_setup_product_idx ON repo.job_setup (product_id);

GRANT SELECT, INSERT, UPDATE, DELETE ON repo.job_setup TO ${runtime_role};
