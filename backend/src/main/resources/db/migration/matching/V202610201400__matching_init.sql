-- Schema matching (FR-13, AD-2): Mentors' Applications to Open Job Posts, one per Mentor and Job.
-- An Application is SUBMITTED, ON_HOLD while another is selected (J3), SELECTED, or ended: CLOSED by
-- the Job (J2, J4) or WITHDRAWN by its Mentor or by the System when the Mentor is locked or revoked.
-- The price is whole VND (test money). Only the Mentor and the Job's Customer read one (AD-20).
-- Personal data: exported for the Mentor, deleted on erasure and Product deletion (MatchingPersonalData).

CREATE SCHEMA matching;

GRANT USAGE ON SCHEMA matching TO ${runtime_role};

CREATE TABLE matching.application
(
    id                         UUID PRIMARY KEY,
    job_id                     UUID        NOT NULL,
    product_id                 UUID        NOT NULL,
    customer_account_id        UUID        NOT NULL,
    mentor_account_id          UUID        NOT NULL,
    status                     TEXT        NOT NULL CHECK (status IN ('SUBMITTED', 'ON_HOLD', 'SELECTED', 'CLOSED',
                                                                      'WITHDRAWN')),
    price_vnd                  BIGINT      NOT NULL CHECK (price_vnd BETWEEN 1000 AND 1000000000),
    timeline_days              INTEGER     NOT NULL CHECK (timeline_days BETWEEN 1 AND 365),
    approach                   TEXT        NOT NULL CHECK (char_length(approach) BETWEEN 1 AND 2000),
    public_code_notice_version TEXT        NOT NULL,
    submitted_at               TIMESTAMPTZ NOT NULL,
    ended_at                   TIMESTAMPTZ,
    end_reason                 TEXT CHECK (end_reason IN ('WITHDRAWN', 'MENTOR_UNAVAILABLE', 'JOB_WITHDRAWN',
                                                          'JOB_LAPSED')),
    UNIQUE (job_id, mentor_account_id),
    CHECK ((status IN ('CLOSED', 'WITHDRAWN')) = (ended_at IS NOT NULL AND end_reason IS NOT NULL))
);

CREATE INDEX application_mentor_idx ON matching.application (mentor_account_id, submitted_at DESC);

CREATE INDEX application_job_idx ON matching.application (job_id, submitted_at DESC);

CREATE INDEX application_product_idx ON matching.application (product_id);

GRANT SELECT, INSERT, UPDATE, DELETE ON matching.application TO ${runtime_role};
