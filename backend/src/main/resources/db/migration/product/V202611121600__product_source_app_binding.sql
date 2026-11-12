-- Story 23.5 (AD-10, AD-13, AD-45, R2, R3): the Source App binding. Each Customer installs the Source App on
-- exactly their Source Repository, and a binding pairs (installation, repository) for that Customer. It is
-- PENDING from the install link until "Kiểm tra" finds an installation that holds the selection rule, BOUND from
-- then on (and to the Product once registered), and ENDED when another binding of the Customer replaces it. The
-- install link's single-use state is kept only as its SHA-256, for an hour; the setup return consumes it and
-- binds nothing. The repository's ID, the installation and its account are known once bound.
-- Personal data: the Customer's account ID and the installation's account; exported, and deleted with the
-- Product or the account (ProductPersonalData).

CREATE TABLE product.source_app_binding
(
    id                   UUID PRIMARY KEY,
    customer_account_id  UUID        NOT NULL,
    repository_full_name TEXT        NOT NULL CHECK (char_length(repository_full_name) BETWEEN 3 AND 140),
    repository_github_id BIGINT CHECK (repository_github_id > 0),
    installation_id      BIGINT CHECK (installation_id > 0),
    account_github_id    BIGINT,
    account_login        TEXT CHECK (char_length(account_login) BETWEEN 1 AND 100),
    account_type         TEXT CHECK (account_type IN ('User', 'Organization')),
    product_id           UUID,
    status               TEXT        NOT NULL CHECK (status IN ('PENDING', 'BOUND', 'ENDED')),
    state_hash           TEXT CHECK (char_length(state_hash) = 64),
    state_expires_at     TIMESTAMPTZ,
    created_at           TIMESTAMPTZ NOT NULL,
    bound_at             TIMESTAMPTZ,
    ended_at             TIMESTAMPTZ,
    CHECK ((status = 'BOUND') <= (repository_github_id IS NOT NULL AND installation_id IS NOT NULL
        AND account_github_id IS NOT NULL AND account_login IS NOT NULL AND account_type IS NOT NULL
        AND bound_at IS NOT NULL)),
    CHECK ((status = 'ENDED') = (ended_at IS NOT NULL)),
    CHECK ((state_hash IS NULL) = (state_expires_at IS NULL)),
    CHECK (product_id IS NULL OR status <> 'PENDING')
);

-- One open draft per Customer and repository name, one open binding per Product, and one owner per state.
CREATE UNIQUE INDEX source_app_binding_draft ON product.source_app_binding (customer_account_id, lower(repository_full_name))
    WHERE product_id IS NULL AND status <> 'ENDED';
CREATE UNIQUE INDEX source_app_binding_product ON product.source_app_binding (product_id)
    WHERE product_id IS NOT NULL AND status <> 'ENDED';
CREATE UNIQUE INDEX source_app_binding_state ON product.source_app_binding (state_hash) WHERE state_hash IS NOT NULL;
CREATE INDEX source_app_binding_installation ON product.source_app_binding (installation_id)
    WHERE installation_id IS NOT NULL;

GRANT SELECT, INSERT, UPDATE, DELETE ON product.source_app_binding TO ${runtime_role};
