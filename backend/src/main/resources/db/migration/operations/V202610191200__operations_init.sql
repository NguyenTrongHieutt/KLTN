-- Schema operations: External Operations, every effect outside the platform and every long internal
-- job (AD-6). Owned by the migration role; the runtime role ${runtime_role} gets DML table by table.

CREATE SCHEMA operations;

GRANT USAGE ON SCHEMA operations TO ${runtime_role};

-- One row per operation, keyed type:causingRecordId. The payload holds no personal data and never
-- changes; what is personal sits in personal_data, with the account it belongs to, and is cleared
-- when the operation completes or the account is erased (AD-24). Rows are kept, never deleted.
CREATE TABLE operations.external_operation
(
    id                UUID PRIMARY KEY,
    operation_key     TEXT        NOT NULL UNIQUE,
    type              TEXT        NOT NULL CHECK (type ~ '^[a-z]+(\.[a-z0-9_]+)+$'),
    lane              TEXT        NOT NULL CHECK (lane IN ('GITHUB_LONG', 'GITHUB_SHORT', 'SCAN', 'EMAIL', 'VNPAY')),
    priority          SMALLINT    NOT NULL CHECK (priority >= 0),
    status            TEXT        NOT NULL CHECK (status IN ('PENDING', 'RUNNING', 'SUCCEEDED', 'ABORTED', 'OBSOLETE', 'FAILED')),
    payload           JSONB       NOT NULL,
    personal_data     JSONB,
    account_id        UUID,
    attempt_count     INT         NOT NULL DEFAULT 0 CHECK (attempt_count >= 0),
    next_attempt_at   TIMESTAMPTZ NOT NULL,
    lease_until       TIMESTAMPTZ,
    read_back_first   BOOLEAN     NOT NULL DEFAULT FALSE,
    result            JSONB,
    error_code        TEXT CHECK (error_code ~ '^[A-Z][A-Z0-9_]*$'),
    created_at        TIMESTAMPTZ NOT NULL,
    completed_at      TIMESTAMPTZ,
    CHECK ((status = 'RUNNING') = (lease_until IS NOT NULL)),
    CHECK ((status IN ('SUCCEEDED', 'ABORTED', 'OBSOLETE', 'FAILED')) = (completed_at IS NOT NULL)),
    CHECK (completed_at IS NULL OR personal_data IS NULL),
    CHECK (result IS NULL OR status = 'SUCCEEDED')
);

-- The pollers' claim: due rows of a lane, by priority (AD-6, AD-34).
CREATE INDEX external_operation_due_idx ON operations.external_operation (lane, priority, next_attempt_at)
    WHERE status IN ('PENDING', 'RUNNING');
CREATE INDEX external_operation_account_idx ON operations.external_operation (account_id)
    WHERE account_id IS NOT NULL;
CREATE INDEX external_operation_failed_idx ON operations.external_operation (completed_at)
    WHERE status = 'FAILED';

-- The key, type, lane and payload are immutable; personal data can only be cleared; a SUCCEEDED,
-- ABORTED or OBSOLETE operation is final. FAILED stays open for the Administrator's retry (FR-64).
CREATE FUNCTION operations.guard_external_operation() RETURNS TRIGGER
    LANGUAGE plpgsql AS
$$
BEGIN
    IF NEW.operation_key <> OLD.operation_key OR NEW.type <> OLD.type OR NEW.lane <> OLD.lane
        OR NEW.payload IS DISTINCT FROM OLD.payload OR NEW.created_at <> OLD.created_at THEN
        RAISE EXCEPTION 'the key, type, lane and payload of an External Operation never change'
            USING ERRCODE = 'integrity_constraint_violation';
    END IF;
    IF NEW.personal_data IS NOT NULL AND NEW.personal_data IS DISTINCT FROM OLD.personal_data THEN
        RAISE EXCEPTION 'personal data of an External Operation can only be cleared'
            USING ERRCODE = 'integrity_constraint_violation';
    END IF;
    IF OLD.status IN ('SUCCEEDED', 'ABORTED', 'OBSOLETE') AND NEW.status <> OLD.status THEN
        RAISE EXCEPTION 'a % External Operation is final', OLD.status
            USING ERRCODE = 'integrity_constraint_violation';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER external_operation_guard
    BEFORE UPDATE ON operations.external_operation
    FOR EACH ROW EXECUTE FUNCTION operations.guard_external_operation();

GRANT SELECT, INSERT, UPDATE ON operations.external_operation TO ${runtime_role};
