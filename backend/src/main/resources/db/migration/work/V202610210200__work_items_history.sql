-- Work Items edited by the Mentor and the Crew (FR-23, AD-23, AD-39): each gains its criteria checklist
-- and creator, and every save writes one append-only history entry (who, when, each changed field with
-- its old and new value, the assignee it gave), in the order seq gives. The runtime role inserts and
-- reads entries and may only clear an erased account from them. Administrators read Work Items only
-- under a grant a case module pushed down (AD-20), each read audited. Seeded Stories get their SEEDED
-- entry here.

ALTER TABLE work.work_item
    ADD COLUMN checklist             JSONB NOT NULL DEFAULT '[]'::jsonb,
    ADD COLUMN created_by_account_id UUID;

CREATE INDEX work_item_creator_idx ON work.work_item (created_by_account_id) WHERE created_by_account_id IS NOT NULL;

CREATE TABLE work.work_item_history
(
    id               UUID PRIMARY KEY,
    seq              BIGINT      NOT NULL GENERATED ALWAYS AS IDENTITY,
    work_item_id     UUID        NOT NULL REFERENCES work.work_item (id),
    job_id           UUID        NOT NULL,
    actor_account_id UUID,
    action           TEXT        NOT NULL CHECK (action IN ('SEEDED', 'CREATED', 'UPDATED')),
    changes          JSONB       NOT NULL,
    assigned_to      UUID,
    at               TIMESTAMPTZ NOT NULL
);

CREATE INDEX work_item_history_item_idx ON work.work_item_history (work_item_id, seq);

CREATE INDEX work_item_history_job_idx ON work.work_item_history (job_id, seq);

CREATE INDEX work_item_history_actor_idx ON work.work_item_history (actor_account_id) WHERE actor_account_id IS NOT NULL;

CREATE INDEX work_item_history_assigned_idx ON work.work_item_history (assigned_to) WHERE assigned_to IS NOT NULL;

GRANT SELECT, INSERT, DELETE ON work.work_item_history TO ${runtime_role};

GRANT UPDATE (actor_account_id, assigned_to) ON work.work_item_history TO ${runtime_role};

INSERT INTO work.work_item_history (id, work_item_id, job_id, action, changes, at)
SELECT gen_random_uuid(), id, job_id, 'SEEDED', '{}'::jsonb, created_at FROM work.work_item WHERE criterion_id IS NOT NULL;

CREATE TABLE work.admin_read_grant
(
    grant_id         UUID PRIMARY KEY,
    admin_account_id UUID  NOT NULL,
    job_id           UUID  NOT NULL,
    items            JSONB NOT NULL,
    reason_id        UUID  NOT NULL,
    ends_at          TIMESTAMPTZ
);

CREATE INDEX admin_read_grant_job_idx ON work.admin_read_grant (job_id, admin_account_id);

GRANT SELECT, INSERT, UPDATE, DELETE ON work.admin_read_grant TO ${runtime_role};
