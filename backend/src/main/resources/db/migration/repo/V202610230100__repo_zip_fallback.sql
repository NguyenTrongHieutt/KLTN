-- Story 8.9 (FR-38, FR-20, SG-15): the ZIP Fallback of a Code Job whose Final Merge cannot happen. One per
-- Job: BUILDING while the ZIP of the Intermediary default branch is built and stored on the Setup (the
-- final ZIP of Story 7.7, which the 30-day deletion reuses), READY once stored and J9 applied. The reason
-- is the Final Merge's refusal, or why the authorization ended.

CREATE TABLE repo.zip_fallback
(
    job_id           UUID PRIMARY KEY,
    authorization_id UUID        NOT NULL REFERENCES repo.final_merge_authorization (id),
    product_id       UUID        NOT NULL,
    reason           TEXT        NOT NULL CHECK (reason IN ('TRIAL_CONFLICT', 'SOURCE_NOT_PUBLIC', 'J9_GUARD', 'PUSH_REFUSED',
                                                          'MERGE_REFUSED', 'NOT_AUTHORIZED', 'REQUEST_NOT_APPROVED',
                                                          'CONFIRMATION_MISSED', 'DECLINED')),
    status           TEXT        NOT NULL CHECK (status IN ('BUILDING', 'READY')),
    archive_file_id  UUID,
    sensitive        JSONB       NOT NULL DEFAULT '[]',
    created_at       TIMESTAMPTZ NOT NULL,
    ready_at         TIMESTAMPTZ,
    CHECK ((status = 'READY') = (ready_at IS NOT NULL AND archive_file_id IS NOT NULL))
);

CREATE INDEX zip_fallback_product ON repo.zip_fallback (product_id);

GRANT SELECT, INSERT, UPDATE, DELETE ON repo.zip_fallback TO ${runtime_role};
