-- AD-24, A-01 (Story 22.1): a Product deletion or the retention run leaves a Job's Work Items with their history, notes
-- and Attachments while a Milestone Split of that Job is not final, as evidence a Split Resolution reads. The row says
-- what waits, and the listener on MilestoneSplitFinalized finishes it once the Job has no open split.
-- Personal data: none; it names a Job and a Product, and goes when the work is finished.

CREATE TABLE work.split_deferral
(
    job_id      UUID PRIMARY KEY,
    product_id  UUID        NOT NULL,
    waiting     TEXT        NOT NULL CHECK (waiting IN ('DELETE', 'RETIRE')),
    deferred_at TIMESTAMPTZ NOT NULL
);

GRANT SELECT, INSERT, UPDATE, DELETE ON work.split_deferral TO ${runtime_role};
