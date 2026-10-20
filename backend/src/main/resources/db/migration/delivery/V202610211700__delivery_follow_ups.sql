-- Follow-up Jobs (FR-11): after an Assessment's Milestone is accepted, the Mentor drafts follow-up Jobs,
-- each citing one numbered recommendation of the accepted Report version, and submits them together;
-- the Customer opens each as a Draft Job Post (CUS-6, then J1) or discards it.
-- Personal data: the Mentor's authorship is cleared on their erasure; a Product's deletion deletes its
-- follow-ups.

CREATE TABLE delivery.follow_up
(
    id                UUID PRIMARY KEY,
    job_id            UUID        NOT NULL,
    product_id        UUID        NOT NULL,
    handover_id       UUID        NOT NULL REFERENCES delivery.handover (id),
    report_page_id    UUID        NOT NULL,
    report_version_no INTEGER     NOT NULL CHECK (report_version_no >= 1),
    recommendation_no INTEGER     NOT NULL CHECK (recommendation_no >= 1),
    title             TEXT        NOT NULL CHECK (char_length(title) BETWEEN 1 AND 120),
    job_types         TEXT[]      NOT NULL CHECK (cardinality(job_types) >= 1),
    skill_ids         UUID[]      NOT NULL CHECK (cardinality(skill_ids) <= 10),
    scope             TEXT        NOT NULL CHECK (char_length(scope) BETWEEN 1 AND 2000),
    criteria          TEXT[]      NOT NULL CHECK (cardinality(criteria) BETWEEN 1 AND 10),
    effort_days       INTEGER     NOT NULL CHECK (effort_days BETWEEN 1 AND 365),
    status            TEXT        NOT NULL CHECK (status IN ('DRAFTING', 'SUBMITTED', 'OPENED', 'DISCARDED')),
    draft_job_id      UUID,
    mentor_account_id UUID,
    created_at        TIMESTAMPTZ NOT NULL,
    updated_at        TIMESTAMPTZ NOT NULL,
    submitted_at      TIMESTAMPTZ,
    decided_at        TIMESTAMPTZ,
    CHECK ((status = 'OPENED') = (draft_job_id IS NOT NULL)),
    CHECK ((status = 'DRAFTING') = (submitted_at IS NULL)),
    CHECK ((status IN ('OPENED', 'DISCARDED')) = (decided_at IS NOT NULL))
);

CREATE INDEX follow_up_job_idx ON delivery.follow_up (job_id, recommendation_no, created_at);

CREATE INDEX follow_up_product_idx ON delivery.follow_up (product_id);

GRANT SELECT, INSERT, DELETE ON delivery.follow_up TO ${runtime_role};

GRANT UPDATE (recommendation_no, title, job_types, skill_ids, scope, criteria, effort_days, status, draft_job_id,
    mentor_account_id, updated_at, submitted_at, decided_at) ON delivery.follow_up TO ${runtime_role};
