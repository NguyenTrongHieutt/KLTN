-- Story 7.6 (FR-20, FR-53, AD-12): Drift Warnings. The daily poll reads each running Code Job's Source
-- Repository by its permanent ID; each new change (a new head of the default branch past the copied
-- commit, or a new state or name) is one warning, stored with the commits that triggered it as evidence,
-- and never repeated. A renamed or transferred repository waits for the Customer's re-confirmation.
-- Personal data: none (the Source Repository is public; the commits are SHAs).

CREATE TABLE repo.drift_warning
(
    id             UUID PRIMARY KEY,
    job_id         UUID        NOT NULL,
    product_id     UUID        NOT NULL,
    kind           TEXT        NOT NULL CHECK (kind IN ('COMMITS', 'DELETED_OR_PRIVATE', 'ARCHIVED', 'RENAMED', 'TRANSFERRED')),
    head_sha       TEXT,
    full_name      TEXT CHECK (length(full_name) BETWEEN 3 AND 140),
    commits        TEXT[]      NOT NULL DEFAULT '{}',
    detected_at    TIMESTAMPTZ NOT NULL,
    reconfirmed_at TIMESTAMPTZ,
    CHECK (kind <> 'COMMITS' OR head_sha IS NOT NULL),
    CHECK (reconfirmed_at IS NULL OR kind IN ('RENAMED', 'TRANSFERRED')),
    CHECK (cardinality(commits) <= 250)
);

CREATE UNIQUE INDEX drift_warning_change_idx ON repo.drift_warning (job_id, kind, coalesce(head_sha, ''), coalesce(full_name, ''));

CREATE INDEX drift_warning_product_idx ON repo.drift_warning (product_id);

GRANT SELECT, INSERT, UPDATE, DELETE ON repo.drift_warning TO ${runtime_role};
