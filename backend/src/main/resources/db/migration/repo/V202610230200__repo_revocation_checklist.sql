-- Story 8.10 (FR-66, AD-7): the Customer's Revocation Checklist when a Job ends, one item per External
-- Access item of the confirmed Agreement. Each is PENDING until the Customer confirms it as REMOVED
-- ("Đã gỡ quyền") or ROTATED ("Đã đổi khóa"); closure never waits for it. The identifiers the Mentor and the
-- Crew entered are kept with the item, so the Customer knows what to remove.
-- Personal data: the Customer's account ID and the identifiers; erasure clears both (RepoPersonalData).

CREATE TABLE repo.revocation_item
(
    job_id              UUID        NOT NULL,
    access_id           UUID        NOT NULL,
    product_id          UUID        NOT NULL,
    customer_account_id UUID,
    system              TEXT        NOT NULL,
    purpose             TEXT        NOT NULL,
    environment         TEXT        NOT NULL CHECK (environment IN ('NON_PRODUCTION', 'PRODUCTION')),
    access_level        TEXT        NOT NULL CHECK (access_level IN ('READ', 'WRITE', 'ADMIN')),
    identifiers         JSONB       NOT NULL DEFAULT '[]',
    status              TEXT        NOT NULL CHECK (status IN ('PENDING', 'CONFIRMED')),
    outcome             TEXT CHECK (outcome IN ('REMOVED', 'ROTATED')),
    confirmed_at        TIMESTAMPTZ,
    created_at          TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (job_id, access_id),
    CHECK ((status = 'CONFIRMED') = (outcome IS NOT NULL AND confirmed_at IS NOT NULL))
);

CREATE INDEX revocation_item_customer ON repo.revocation_item (customer_account_id) WHERE customer_account_id IS NOT NULL;
CREATE INDEX revocation_item_product ON repo.revocation_item (product_id);

GRANT SELECT, INSERT, UPDATE, DELETE ON repo.revocation_item TO ${runtime_role};
