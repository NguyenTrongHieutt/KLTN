-- FR-8, FR-65: a Finding its resolver marks as not real, with the written reason Administrators can
-- review and reopen (FR-64, Epic 10); and re-scans the Customer asks for. The reason is the
-- Customer's own words about the match, never the matched value.

ALTER TABLE scanning.finding DROP CONSTRAINT finding_status_check;

ALTER TABLE scanning.finding
    ADD CONSTRAINT finding_status_check CHECK (status IN ('OPEN', 'RESOLVED', 'FALSE_POSITIVE'));

ALTER TABLE scanning.finding DROP CONSTRAINT finding_resolution_check;

ALTER TABLE scanning.finding
    ADD CONSTRAINT finding_resolution_check CHECK (resolution IN ('ROTATED', 'CLEAN_REPOSITORY', 'FALSE_POSITIVE'));

ALTER TABLE scanning.finding
    ADD COLUMN resolution_reason TEXT CHECK (char_length(resolution_reason) BETWEEN 1 AND 500);

ALTER TABLE scanning.finding
    ADD CONSTRAINT finding_false_positive_check CHECK (
        (status = 'FALSE_POSITIVE') = (resolution IS NOT DISTINCT FROM 'FALSE_POSITIVE')
        AND (resolution IS NOT DISTINCT FROM 'FALSE_POSITIVE') = (resolution_reason IS NOT NULL));

ALTER TABLE scanning.scan_run DROP CONSTRAINT scan_run_purpose_check;

ALTER TABLE scanning.scan_run
    ADD CONSTRAINT scan_run_purpose_check CHECK (purpose IN ('INTAKE', 'CLEAN_REPOSITORY', 'RESCAN'));
