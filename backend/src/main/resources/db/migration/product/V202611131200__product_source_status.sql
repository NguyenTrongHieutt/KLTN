-- Story 23.5c (AD-45, R7, R12): each Product's last known Source App and workflow status, whatever check found it
-- ("Kiểm tra lại" on CUS-4, the Source checks before Setup and the Final Merge, the re-confirmation, the daily drift
-- read, or a contributor read the binding refused), so CUS-2 shows it without a GitHub call. workflow_blob is the
-- accepted vibefix.yml blob the file matched when the status was read as READY, whose end date the daily job follows;
-- none for any other status. Nothing is known of the Products registered so far, so each reads READY with no blob and
-- no check time; registration records READY and its blob from now on. Not personal data.

ALTER TABLE product.product
    ADD COLUMN source_status     TEXT        NOT NULL DEFAULT 'READY'
        CHECK (source_status IN ('READY', 'SOURCE_APP_MISSING', 'SOURCE_APP_WRONG_REPOSITORY', 'WORKFLOW_MISSING',
                                 'WORKFLOW_MISMATCH')),
    ADD COLUMN source_checked_at TIMESTAMPTZ,
    ADD COLUMN workflow_blob     TEXT CHECK (workflow_blob ~ '^[0-9a-f]{40}$'),
    ADD CONSTRAINT product_workflow_blob_ready_check CHECK (workflow_blob IS NULL OR source_status = 'READY');

-- The daily end-date job reads the live Products that hold a blob.
CREATE INDEX product_workflow_blob_idx ON product.product (workflow_blob) WHERE workflow_blob IS NOT NULL AND deleted_at IS NULL;
