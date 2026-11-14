-- Schema github (AD-11, AD-44, AD-46, FR-32, Story 23.8a): the path report a handover-copy run posts between its
-- fetch and its push. The run lists every path its range changed, with rename detection off, and checks
-- .github/workflows with git diff --quiet; it posts both to POST /github/actions/report, authenticated like the
-- exchange by a fresh OIDC ID token and the same correlation. The list reaches vibefix through this one channel:
-- it is never written to a file of a platform repository, and never logged.
-- One report per correlation, so a second post is a replay. Each row is deleted with its correlation, 30 days
-- after that correlation's expiry.
-- Personal data: none; the paths are the team's file names, which the handover's Sensitive Changes already keep.

CREATE TABLE github.exchange_report
(
    correlation_id    UUID PRIMARY KEY REFERENCES github.exchange_correlation (id) ON DELETE CASCADE,
    workflows_changed BOOLEAN     NOT NULL,
    -- The NUL-separated UTF-8 paths, gzipped: the run's own bytes, at most 2 MB as the endpoint caps the body.
    paths_gzip        BYTEA       NOT NULL CHECK (length(paths_gzip) <= 2097152),
    path_count        INTEGER     NOT NULL CHECK (path_count BETWEEN 0 AND 100000),
    reported_at       TIMESTAMPTZ NOT NULL
);

GRANT SELECT, INSERT, DELETE ON github.exchange_report TO ${runtime_role};
