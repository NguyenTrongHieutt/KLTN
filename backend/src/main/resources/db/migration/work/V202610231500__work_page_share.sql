-- Story 17.1 (FR-70, AD-41): the earlier Jobs whose customer-visible Pages a Customer shares with a new Job's
-- Mentor and active Crew. The new Job may have no Board yet (it is in Agreement); the earlier Job has one.
-- Who reads, and until when, is decided at each request from job (the Mentor, the ACTIVE Crew rows, the
-- new Job's state); an unshared Job's row is deleted, and the audit log keeps both commands.
CREATE TABLE work.page_share (
    job_id        UUID        NOT NULL,
    source_job_id UUID        NOT NULL REFERENCES work.board (job_id),
    created_at    TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (job_id, source_job_id),
    CHECK (job_id <> source_job_id)
);

CREATE INDEX page_share_source_idx ON work.page_share (source_job_id);

GRANT SELECT, INSERT, DELETE ON work.page_share TO ${runtime_role};
