-- Schema timers: Deadlines instead of in-memory timers (AD-7). Owned by the migration role; the
-- runtime role ${runtime_role} gets DML table by table.

CREATE SCHEMA timers;

GRANT USAGE ON SCHEMA timers TO ${runtime_role};

-- One time-based rule. A pending row has its due time; a paused one (its Job frozen) has the time it
-- had left instead. A finished row keeps how it ended and is purged 30 days later.
CREATE TABLE timers.deadline
(
    id           UUID PRIMARY KEY,
    kind         TEXT        NOT NULL CHECK (kind ~ '^[a-z]+(\.[a-z0-9_]+)+$'),
    owner        TEXT        NOT NULL CHECK (owner ~ '^[a-z]+$'),
    subject_id   UUID        NOT NULL,
    generation   TEXT        NOT NULL CHECK (char_length(generation) BETWEEN 1 AND 200),
    job_id       UUID,
    pausable     BOOLEAN     NOT NULL,
    status       TEXT        NOT NULL CHECK (status IN ('PENDING', 'PAUSED', 'FIRED', 'OBSOLETE')),
    due_at       TIMESTAMPTZ,
    remaining_ms BIGINT CHECK (remaining_ms >= 0),
    created_at   TIMESTAMPTZ NOT NULL,
    finished_at  TIMESTAMPTZ,
    CHECK ((status = 'PENDING') = (due_at IS NOT NULL)),
    CHECK ((status = 'PAUSED') = (remaining_ms IS NOT NULL)),
    CHECK ((status IN ('FIRED', 'OBSOLETE')) = (finished_at IS NOT NULL)),
    CHECK (NOT pausable OR job_id IS NOT NULL)
);

CREATE INDEX deadline_due ON timers.deadline (due_at) WHERE status = 'PENDING';
CREATE INDEX deadline_job ON timers.deadline (job_id) WHERE job_id IS NOT NULL AND status IN ('PENDING', 'PAUSED');
CREATE INDEX deadline_finished ON timers.deadline (finished_at) WHERE finished_at IS NOT NULL;

GRANT SELECT, INSERT, UPDATE, DELETE ON timers.deadline TO ${runtime_role};

-- The Jobs frozen now (a Dispute), and since when: a pausable Deadline scheduled meanwhile starts paused.
CREATE TABLE timers.frozen_job
(
    job_id    UUID PRIMARY KEY,
    frozen_at TIMESTAMPTZ NOT NULL
);

GRANT SELECT, INSERT, DELETE ON timers.frozen_job TO ${runtime_role};
