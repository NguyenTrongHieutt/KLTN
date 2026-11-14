-- Schema delivery (FR-32, AD-11, AD-46, Story 23.8a): "Gửi bàn giao" of a Code Job became asynchronous. The form's
-- checks run as before, and what the handover would store is kept here while the runner copies the Handover Commit
-- into handover/{id} of the Intermediary Repository. No delivery.handover row exists yet: when the copy ends,
-- delivery records the handover under this submission's own ID, or ends the submission with a named reason
-- (WORKFLOW_FILES_CHANGED, RUN_FAILED, EXCHANGE_REFUSED, PATH_LIST_UNREADABLE, or M2's STALE). One submission may
-- be pending per Milestone at a time. No busy marker is held while the runner copies (AD-46), so the partial
-- unique index below is what keeps a second copy of one Milestone out.
-- The draft holds the Mentor's marks and notes, already FR-47 masked, the Milestone's criteria as they stood, the
-- notes on unfinished Work Items, the preview or the recording, and the screenshot IDs; it holds no code.
-- Personal data: the Mentor who sent it and the Customer it waits for; an erasure of either deletes the row, since
-- the draft names both, and a Product deletion deletes them all (DeliveryPersonalData).

CREATE TABLE delivery.handover_submission
(
    id                UUID        PRIMARY KEY,
    job_id            UUID        NOT NULL,
    product_id        UUID        NOT NULL,
    milestone_id      UUID        NOT NULL,
    milestone_number  INTEGER     NOT NULL CHECK (milestone_number >= 1),
    mentor_account_id UUID,
    customer_account_id UUID,
    job_version       BIGINT      NOT NULL CHECK (job_version >= 1),
    range_start       TEXT        NOT NULL CHECK (range_start ~ '^[0-9a-f]{40}$'),
    handover_commit   TEXT        NOT NULL CHECK (handover_commit ~ '^[0-9a-f]{40}$'),
    draft             JSONB       NOT NULL,
    status            TEXT        NOT NULL CHECK (status IN ('PENDING', 'DONE', 'REFUSED')),
    reason            TEXT CHECK (reason ~ '^[A-Z][A-Z0-9_]{0,60}$'),
    -- AD-23: the module whose reason enum names it, so the screen reads reason.{module}.{REASON}.
    reason_module     TEXT CHECK (reason_module IN ('delivery', 'job')),
    created_at        TIMESTAMPTZ NOT NULL,
    finished_at       TIMESTAMPTZ,
    CHECK ((status = 'REFUSED') = (reason IS NOT NULL)),
    CHECK ((reason IS NULL) = (reason_module IS NULL)),
    CHECK ((status = 'PENDING') = (finished_at IS NULL))
);

-- FR-32: one pending submission per Milestone, so a second "Gửi bàn giao" never starts a second copy.
CREATE UNIQUE INDEX handover_submission_pending_idx ON delivery.handover_submission (milestone_id)
    WHERE status = 'PENDING';

CREATE INDEX handover_submission_job_idx ON delivery.handover_submission (job_id);

CREATE INDEX handover_submission_product_idx ON delivery.handover_submission (product_id);

CREATE INDEX handover_submission_mentor_idx ON delivery.handover_submission (mentor_account_id)
    WHERE mentor_account_id IS NOT NULL;

CREATE INDEX handover_submission_customer_idx ON delivery.handover_submission (customer_account_id)
    WHERE customer_account_id IS NOT NULL;

GRANT SELECT, INSERT, UPDATE, DELETE ON delivery.handover_submission TO ${runtime_role};

-- FR-32, Story 23.8a: the copy run reports every changed path of the range, with no 300-file cap, so a handover can
-- hold far more Sensitive Changes than the compare ever showed. The rows are capped at the first 300 and this column
-- keeps how many the whole range held, which the Delivery Report's caption names [ASSUMPTION].
ALTER TABLE delivery.handover
    ADD COLUMN sensitive_change_count INTEGER NOT NULL DEFAULT 0 CHECK (sensitive_change_count >= 0);
