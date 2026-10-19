-- FR-65: scanning a clean repository a Customer offers in place of the Source Repository. The
-- candidate's run is the Product's latest scan only once the Product adopts it (product switched to
-- it); a personal-data Finding it clears is resolved as CLEAN_REPOSITORY.

ALTER TABLE scanning.scan_run DROP CONSTRAINT scan_run_purpose_check;

ALTER TABLE scanning.scan_run
    ADD CONSTRAINT scan_run_purpose_check CHECK (purpose IN ('INTAKE', 'CLEAN_REPOSITORY'));

ALTER TABLE scanning.scan_run ADD COLUMN adopted BOOLEAN NOT NULL DEFAULT false;

ALTER TABLE scanning.finding DROP CONSTRAINT finding_resolution_check;

ALTER TABLE scanning.finding
    ADD CONSTRAINT finding_resolution_check CHECK (resolution IN ('ROTATED', 'CLEAN_REPOSITORY'));
