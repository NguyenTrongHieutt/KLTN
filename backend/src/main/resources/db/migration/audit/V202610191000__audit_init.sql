-- Schema audit: the append-only Audit Log (AD-8, FR-63, NFR-7).
-- Owned by the migration role. The runtime role ${runtime_role} may only insert and read, and the
-- triggers reject every UPDATE, DELETE and TRUNCATE by any role. Audit migrations are additive only.
-- Entries hold internal IDs and codes only, so nothing here is personal data (AD-24).

CREATE SCHEMA audit;

GRANT USAGE ON SCHEMA audit TO ${runtime_role};

CREATE TABLE audit.audit_entry
(
    id               UUID PRIMARY KEY,
    occurred_at      TIMESTAMPTZ NOT NULL,
    actor_type       TEXT        NOT NULL CHECK (actor_type IN ('USER', 'SYSTEM')),
    actor_account_id UUID,
    action           TEXT        NOT NULL CHECK (action ~ '^[a-z]+(\.[a-z0-9_]+)+$'),
    job_id           UUID,
    correlation_id   UUID,
    CHECK ((actor_type = 'USER') = (actor_account_id IS NOT NULL))
);

CREATE INDEX audit_entry_job_idx ON audit.audit_entry (job_id, occurred_at) WHERE job_id IS NOT NULL;
CREATE INDEX audit_entry_actor_idx ON audit.audit_entry (actor_account_id, occurred_at)
    WHERE actor_account_id IS NOT NULL;

CREATE TABLE audit.audit_entry_object
(
    entry_id    UUID NOT NULL REFERENCES audit.audit_entry (id),
    position    INT  NOT NULL CHECK (position >= 0),
    object_type TEXT NOT NULL CHECK (object_type ~ '^[a-z]+(\.[a-z0-9_]+)+$'),
    object_id   UUID NOT NULL,
    PRIMARY KEY (entry_id, position)
);

CREATE INDEX audit_entry_object_object_idx ON audit.audit_entry_object (object_id);

CREATE FUNCTION audit.reject_change() RETURNS TRIGGER
    LANGUAGE plpgsql AS
$$
BEGIN
    RAISE EXCEPTION 'audit entries are append-only (% on %)', TG_OP, TG_TABLE_NAME
        USING ERRCODE = 'insufficient_privilege';
END;
$$;

CREATE TRIGGER audit_entry_append_only
    BEFORE UPDATE OR DELETE ON audit.audit_entry
    FOR EACH ROW EXECUTE FUNCTION audit.reject_change();
CREATE TRIGGER audit_entry_no_truncate
    BEFORE TRUNCATE ON audit.audit_entry
    FOR EACH STATEMENT EXECUTE FUNCTION audit.reject_change();
CREATE TRIGGER audit_entry_object_append_only
    BEFORE UPDATE OR DELETE ON audit.audit_entry_object
    FOR EACH ROW EXECUTE FUNCTION audit.reject_change();
CREATE TRIGGER audit_entry_object_no_truncate
    BEFORE TRUNCATE ON audit.audit_entry_object
    FOR EACH STATEMENT EXECUTE FUNCTION audit.reject_change();

GRANT SELECT, INSERT ON audit.audit_entry TO ${runtime_role};
GRANT SELECT, INSERT ON audit.audit_entry_object TO ${runtime_role};
