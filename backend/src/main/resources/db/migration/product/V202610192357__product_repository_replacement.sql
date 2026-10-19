-- FR-65: a clean repository the Customer offers in place of the Source Repository. A replacement
-- waits for its full scan; only a scan without personal data accepts it, switching the Product to the
-- new repository's ID. Accepted rows keep the previous repository, so the Product's history keeps
-- every link it had. Personal data: the requester (ProductPersonalData).

CREATE TABLE product.repository_replacement
(
    id                         UUID PRIMARY KEY,
    product_id                 UUID        NOT NULL REFERENCES product.product (id) ON DELETE CASCADE,
    requested_by_account_id    UUID        NOT NULL,
    repository_github_id       BIGINT      NOT NULL CHECK (repository_github_id > 0),
    repository_full_name       TEXT        NOT NULL CHECK (char_length(repository_full_name) BETWEEN 3 AND 140),
    repository_owner_type      TEXT        NOT NULL CHECK (repository_owner_type IN ('User', 'Organization')),
    repository_default_branch  TEXT        NOT NULL CHECK (char_length(repository_default_branch) BETWEEN 1 AND 255),
    repository_fork_parent     TEXT CHECK (char_length(repository_fork_parent) <= 140),
    previous_github_id         BIGINT CHECK (previous_github_id > 0),
    previous_full_name         TEXT CHECK (char_length(previous_full_name) BETWEEN 3 AND 140),
    status                     TEXT        NOT NULL CHECK (status IN ('PENDING', 'ACCEPTED', 'REJECTED', 'SUPERSEDED')),
    reason                     TEXT CHECK (reason ~ '^[A-Z][A-Z0-9_]*$'),
    scan_run_id                UUID        NOT NULL,
    requested_at               TIMESTAMPTZ NOT NULL,
    decided_at                 TIMESTAMPTZ,
    CHECK ((status = 'PENDING') = (decided_at IS NULL)),
    CHECK ((status = 'ACCEPTED') = (previous_github_id IS NOT NULL))
);

CREATE INDEX repository_replacement_product_idx ON product.repository_replacement (product_id, requested_at DESC);

CREATE UNIQUE INDEX repository_replacement_scan_idx ON product.repository_replacement (scan_run_id);

CREATE INDEX repository_replacement_requester_idx ON product.repository_replacement (requested_by_account_id);

GRANT SELECT, INSERT, UPDATE, DELETE ON product.repository_replacement TO ${runtime_role};
