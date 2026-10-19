-- Schema product (FR-7, AD-13, NFR-5): a Customer's Products and each one's Source Repository, kept
-- by GitHub's permanent numeric repository ID so a renamed repository is still recognized. The three
-- CUS-3 acknowledgments (N-1, N-1b, N-2) are recorded with their notice versions and one time.
-- Personal data: exported, and deleted on erasure (ProductPersonalData).

CREATE SCHEMA product;

GRANT USAGE ON SCHEMA product TO ${runtime_role};

CREATE TABLE product.product
(
    id                        UUID PRIMARY KEY,
    customer_account_id       UUID        NOT NULL,
    name                      TEXT        NOT NULL CHECK (char_length(name) BETWEEN 1 AND 80),
    description               TEXT        NOT NULL CHECK (char_length(description) BETWEEN 1 AND 1000),
    demo_url                  TEXT CHECK (char_length(demo_url) <= 300 AND demo_url ~ '^https://'),
    ai_tools                  TEXT[]      NOT NULL CHECK (cardinality(ai_tools) BETWEEN 1 AND 9),
    repository_github_id      BIGINT      NOT NULL CHECK (repository_github_id > 0),
    repository_full_name      TEXT        NOT NULL CHECK (char_length(repository_full_name) BETWEEN 3 AND 140),
    repository_owner_type     TEXT        NOT NULL CHECK (repository_owner_type IN ('User', 'Organization')),
    repository_default_branch TEXT        NOT NULL CHECK (char_length(repository_default_branch) BETWEEN 1 AND 255),
    repository_fork_parent    TEXT CHECK (char_length(repository_fork_parent) <= 140),
    -- N-1 (the code is public), N-1b (the copy permission, PRD addendum B3.2), N-2 (detection limits).
    public_notice_version     TEXT        NOT NULL CHECK (char_length(public_notice_version) BETWEEN 1 AND 20),
    copy_permission_version   TEXT        NOT NULL CHECK (char_length(copy_permission_version) BETWEEN 1 AND 20),
    detection_notice_version  TEXT        NOT NULL CHECK (char_length(detection_notice_version) BETWEEN 1 AND 20),
    acknowledged_at           TIMESTAMPTZ NOT NULL,
    created_at                TIMESTAMPTZ NOT NULL,
    deleted_at                TIMESTAMPTZ
);

-- One live Product per Source Repository (REPO_ALREADY_LINKED).
CREATE UNIQUE INDEX product_repository_live_idx ON product.product (repository_github_id) WHERE deleted_at IS NULL;

CREATE INDEX product_customer_idx ON product.product (customer_account_id, created_at);

GRANT SELECT, INSERT, UPDATE, DELETE ON product.product TO ${runtime_role};
