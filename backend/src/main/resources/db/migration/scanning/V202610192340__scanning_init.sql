-- Schema scanning (FR-8, AD-18): Secret Scan runs and the Findings they open. A run records what it
-- scanned and when; a Finding holds the rule, file, commit, date, a masked preview, and an HMAC
-- fingerprint that de-duplicates it within its Product, never the credential itself (the preview's
-- length check keeps a raw value out). Personal data: the run names the Customer it notifies;
-- exported, and deleted with their Findings on erasure (ScanningPersonalData).

CREATE SCHEMA scanning;

GRANT USAGE ON SCHEMA scanning TO ${runtime_role};

CREATE TABLE scanning.scan_run
(
    id                   UUID PRIMARY KEY,
    product_id           UUID        NOT NULL,
    customer_account_id  UUID        NOT NULL,
    product_name         TEXT        NOT NULL CHECK (char_length(product_name) BETWEEN 1 AND 80),
    repository_full_name TEXT        NOT NULL CHECK (char_length(repository_full_name) BETWEEN 3 AND 140),
    repository_github_id BIGINT      NOT NULL CHECK (repository_github_id > 0),
    purpose              TEXT        NOT NULL CHECK (purpose IN ('INTAKE')),
    -- The scanned range; both empty for the whole history.
    base_sha             TEXT CHECK (base_sha ~ '^[0-9a-f]{40}$'),
    head_sha             TEXT CHECK (head_sha ~ '^[0-9a-f]{40}$'),
    status               TEXT        NOT NULL CHECK (status IN ('PENDING', 'RUNNING', 'RETRYING', 'SUCCEEDED', 'FAILED')),
    attempts             INTEGER     NOT NULL DEFAULT 0 CHECK (attempts >= 0),
    rules_version        INTEGER CHECK (rules_version > 0),
    commits              INTEGER CHECK (commits >= 0),
    scanned_files        INTEGER CHECK (scanned_files >= 0),
    -- Files over the size limit: counted, and the first 50 listed with their size ("chưa kiểm tra được").
    unscanned_count      INTEGER     NOT NULL DEFAULT 0 CHECK (unscanned_count >= 0),
    unscanned            JSONB       NOT NULL DEFAULT '[]',
    -- Distinct credentials this run found, and how many of them opened a new Finding.
    found_count          INTEGER CHECK (found_count >= 0),
    new_findings         INTEGER CHECK (new_findings >= 0),
    -- Why the walk stopped early (a cap), or why the run failed.
    stopped              TEXT CHECK (stopped ~ '^[A-Z][A-Z0-9_]*$'),
    error_code           TEXT CHECK (error_code ~ '^[A-Z][A-Z0-9_]*$'),
    requested_at         TIMESTAMPTZ NOT NULL,
    started_at           TIMESTAMPTZ,
    finished_at          TIMESTAMPTZ,
    CHECK ((status IN ('SUCCEEDED', 'FAILED')) = (finished_at IS NOT NULL))
);

CREATE INDEX scan_run_product_idx ON scanning.scan_run (product_id, requested_at DESC, id);

CREATE INDEX scan_run_customer_idx ON scanning.scan_run (customer_account_id);

CREATE TABLE scanning.finding
(
    id             UUID PRIMARY KEY,
    product_id     UUID        NOT NULL,
    -- The run that opened it.
    scan_run_id    UUID        NOT NULL REFERENCES scanning.scan_run (id) ON DELETE CASCADE,
    rule_id        TEXT        NOT NULL CHECK (rule_id ~ '^[a-z0-9-]{1,60}$'),
    rule_type      TEXT        NOT NULL CHECK (char_length(rule_type) BETWEEN 1 AND 80),
    file_path      TEXT        NOT NULL CHECK (char_length(file_path) BETWEEN 1 AND 4096),
    commit_sha     TEXT        NOT NULL CHECK (commit_sha ~ '^[0-9a-f]{40}$'),
    committed_at   TIMESTAMPTZ NOT NULL,
    -- At most 4 characters at each end around 8 bullets (FR-8).
    masked_preview TEXT        NOT NULL CHECK (char_length(masked_preview) BETWEEN 8 AND 16),
    fingerprint    TEXT        NOT NULL CHECK (fingerprint ~ '^[0-9a-f]{64}$'),
    status         TEXT        NOT NULL CHECK (status IN ('OPEN')),
    created_at     TIMESTAMPTZ NOT NULL
);

-- One Finding per credential per Product, however often the history repeats it.
CREATE UNIQUE INDEX finding_fingerprint_idx ON scanning.finding (product_id, fingerprint);

CREATE INDEX finding_scan_run_idx ON scanning.finding (scan_run_id);

GRANT SELECT, INSERT, UPDATE, DELETE ON scanning.scan_run, scanning.finding TO ${runtime_role};
