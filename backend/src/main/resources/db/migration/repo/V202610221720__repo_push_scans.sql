-- Story 7.4 (FR-8, AD-12, SG-16): the branch and tag heads of each Working Repository as repo last read
-- them, and the push scans it requested. A webhook or the 10-minute sweep re-reads the heads; any new or
-- moved ref requests a PUSH scan. Setup records the copied default branch at its approved commit.
-- Personal data: none.

CREATE TABLE repo.branch_head
(
    repository_github_id BIGINT      NOT NULL,
    ref                  TEXT        NOT NULL CHECK (ref ~ '^refs/(heads|tags)/.{1,250}$'),
    job_id               UUID        NOT NULL,
    sha                  TEXT        NOT NULL CHECK (sha ~ '^[0-9a-f]{40}$'),
    updated_at           TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (repository_github_id, ref)
);

CREATE INDEX branch_head_job_idx ON repo.branch_head (job_id);

GRANT SELECT, INSERT, UPDATE, DELETE ON repo.branch_head TO ${runtime_role};

CREATE TABLE repo.push_scan
(
    scan_run_id          UUID PRIMARY KEY,
    job_id               UUID        NOT NULL,
    product_id           UUID        NOT NULL,
    repository_github_id BIGINT      NOT NULL,
    requested_at         TIMESTAMPTZ NOT NULL,
    delay_alerted_at     TIMESTAMPTZ
);

CREATE INDEX push_scan_job_idx ON repo.push_scan (job_id);

CREATE INDEX push_scan_product_idx ON repo.push_scan (product_id);

GRANT SELECT, INSERT, UPDATE, DELETE ON repo.push_scan TO ${runtime_role};
