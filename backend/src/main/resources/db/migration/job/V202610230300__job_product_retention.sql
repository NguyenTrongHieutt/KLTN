-- Story 13.5 (NFR-10, AD-24): each retirement of a Product's Job data, 12 months after its last Job ended,
-- named by the Job whose end started it. The Product and its records under NFR-10's exceptions stay.

CREATE TABLE job.product_retention
(
    product_id UUID        NOT NULL,
    job_id     UUID        NOT NULL,
    retired_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (product_id, job_id)
);

GRANT SELECT, INSERT, DELETE ON job.product_retention TO ${runtime_role};
