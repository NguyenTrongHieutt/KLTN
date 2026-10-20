-- Schema delivery (FR-10, FR-32, AD-2, AD-4, AD-33): Milestone Handovers and their Delivery Reports. A
-- handover belongs to a Job and one of its Milestones, numbered by round within the Milestone, and keeps
-- the Job version M2 was applied on and the transition. It snapshots each Acceptance Criterion of the
-- Milestone with the Mentor's own met-or-not mark and note, never counted as a criterion done (AD-33),
-- and a note for each Work Item that was not done, which no Customer endpoint returns (AD-39). An
-- Assessment Job's handover pins the Assessment Report Page at its version then (SG-25). Rows are never
-- rewritten; the runtime role may only clear an erased Mentor.
-- Personal data: exported for the Mentor who made it, the Mentor cleared on erasure, deleted with the
-- Product (DeliveryPersonalData).

CREATE SCHEMA delivery;

GRANT USAGE ON SCHEMA delivery TO ${runtime_role};

CREATE TABLE delivery.handover
(
    id                UUID PRIMARY KEY,
    seq               BIGINT      NOT NULL GENERATED ALWAYS AS IDENTITY,
    job_id            UUID        NOT NULL,
    product_id        UUID        NOT NULL,
    milestone_id      UUID        NOT NULL,
    milestone_number  INTEGER     NOT NULL CHECK (milestone_number >= 1),
    milestone_title   TEXT        NOT NULL CHECK (char_length(milestone_title) BETWEEN 1 AND 200),
    round             INTEGER     NOT NULL CHECK (round >= 1),
    mentor_account_id UUID,
    status            TEXT        NOT NULL CHECK (status IN ('SUBMITTED')),
    job_version       BIGINT      NOT NULL CHECK (job_version >= 1),
    transition_id     UUID        NOT NULL UNIQUE,
    report_page_id    UUID,
    report_version_no INTEGER CHECK (report_version_no >= 1),
    check_by          TIMESTAMPTZ NOT NULL,
    created_at        TIMESTAMPTZ NOT NULL,
    UNIQUE (milestone_id, round),
    CHECK ((report_page_id IS NULL) = (report_version_no IS NULL))
);

CREATE INDEX handover_job_idx ON delivery.handover (job_id, seq);

CREATE INDEX handover_product_idx ON delivery.handover (product_id);

CREATE INDEX handover_mentor_idx ON delivery.handover (mentor_account_id) WHERE mentor_account_id IS NOT NULL;

GRANT SELECT, INSERT, DELETE ON delivery.handover TO ${runtime_role};

GRANT UPDATE (mentor_account_id) ON delivery.handover TO ${runtime_role};

CREATE TABLE delivery.handover_criterion
(
    handover_id  UUID    NOT NULL REFERENCES delivery.handover (id),
    criterion_id UUID    NOT NULL,
    number       INTEGER NOT NULL CHECK (number >= 1),
    statement    TEXT    NOT NULL,
    check_steps  TEXT    NOT NULL,
    met          BOOLEAN NOT NULL,
    note         TEXT    NOT NULL CHECK (char_length(note) BETWEEN 1 AND 2000),
    PRIMARY KEY (handover_id, criterion_id)
);

GRANT SELECT, INSERT, DELETE ON delivery.handover_criterion TO ${runtime_role};

CREATE TABLE delivery.handover_item_note
(
    handover_id  UUID NOT NULL REFERENCES delivery.handover (id),
    work_item_id UUID NOT NULL,
    item_key     TEXT NOT NULL,
    title        TEXT NOT NULL,
    status       TEXT NOT NULL CHECK (status IN ('TODO', 'IN_PROGRESS', 'IN_REVIEW', 'DONE')),
    note         TEXT NOT NULL CHECK (char_length(note) BETWEEN 1 AND 2000),
    PRIMARY KEY (handover_id, work_item_id)
);

GRANT SELECT, INSERT, DELETE ON delivery.handover_item_note TO ${runtime_role};
