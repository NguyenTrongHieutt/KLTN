-- Schema reputation (FR-49, FR-78, AD-2, AD-20, AD-33): the Rating Window J9 opens for a Job, the
-- Ratings (the Customer and the Mentor of each other, blind until both rated or the window ended, and
-- the Mentor of each Crew member who was ever active, hidden until the end), and each Crew member's
-- private feedback about the Mentor, which only Administrators read. A Rating or a feedback is given
-- once and never changed. Private feedback is kept 24 months after its window [ASSUMPTION].
-- Personal data: exported for its author (a Mentor's export never holds feedback about them), cleared
-- or deleted on erasure, deleted with the Product (ReputationPersonalData).

CREATE SCHEMA reputation;

GRANT USAGE ON SCHEMA reputation TO ${runtime_role};

CREATE TABLE reputation.rating_window
(
    job_id              UUID PRIMARY KEY,
    product_id          UUID        NOT NULL,
    customer_account_id UUID,
    mentor_account_id   UUID,
    job_title           TEXT        NOT NULL,
    opened_at           TIMESTAMPTZ NOT NULL,
    ends_at             TIMESTAMPTZ NOT NULL,
    pair_published_at   TIMESTAMPTZ,
    crew_published_at   TIMESTAMPTZ,
    CHECK (ends_at > opened_at)
);

CREATE INDEX rating_window_product_idx ON reputation.rating_window (product_id);

GRANT SELECT, INSERT, DELETE ON reputation.rating_window TO ${runtime_role};

GRANT UPDATE (pair_published_at, crew_published_at, customer_account_id, mentor_account_id, job_title)
    ON reputation.rating_window TO ${runtime_role};

CREATE TABLE reputation.rating
(
    id                UUID PRIMARY KEY,
    job_id            UUID        NOT NULL REFERENCES reputation.rating_window (job_id),
    kind              TEXT        NOT NULL CHECK (kind IN ('CUSTOMER_TO_MENTOR', 'MENTOR_TO_CUSTOMER', 'MENTOR_TO_STUDENT')),
    rater_account_id  UUID,
    ratee_account_id  UUID        NOT NULL,
    stars             INTEGER     NOT NULL CHECK (stars BETWEEN 1 AND 5),
    comment           TEXT        NOT NULL CHECK (char_length(comment) BETWEEN 1 AND 2000),
    created_at        TIMESTAMPTZ NOT NULL
);

CREATE UNIQUE INDEX rating_once_idx ON reputation.rating (job_id, kind, ratee_account_id);

CREATE INDEX rating_ratee_idx ON reputation.rating (ratee_account_id);

CREATE INDEX rating_rater_idx ON reputation.rating (rater_account_id) WHERE rater_account_id IS NOT NULL;

GRANT SELECT, INSERT, DELETE ON reputation.rating TO ${runtime_role};

GRANT UPDATE (rater_account_id) ON reputation.rating TO ${runtime_role};

CREATE TABLE reputation.private_feedback
(
    id                 UUID PRIMARY KEY,
    job_id             UUID        NOT NULL REFERENCES reputation.rating_window (job_id),
    mentor_account_id  UUID        NOT NULL,
    student_account_id UUID        NOT NULL,
    stars              INTEGER     NOT NULL CHECK (stars BETWEEN 1 AND 5),
    comment            TEXT CHECK (char_length(comment) <= 2000),
    created_at         TIMESTAMPTZ NOT NULL,
    delete_after       TIMESTAMPTZ NOT NULL,
    UNIQUE (job_id, student_account_id)
);

CREATE INDEX private_feedback_mentor_idx ON reputation.private_feedback (mentor_account_id);

CREATE INDEX private_feedback_student_idx ON reputation.private_feedback (student_account_id);

GRANT SELECT, INSERT, DELETE ON reputation.private_feedback TO ${runtime_role};
