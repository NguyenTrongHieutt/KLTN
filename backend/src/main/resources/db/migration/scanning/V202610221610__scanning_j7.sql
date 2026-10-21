-- FR-18, SG-9, Story 7.2: a scan run when a Code Job starts (purpose J7) checks the whole history again,
-- and every successful run keeps the default branch and its head as it read them, which a J7 run
-- approves for the copy into the Intermediary Repository.

ALTER TABLE scanning.scan_run DROP CONSTRAINT scan_run_purpose_check;

ALTER TABLE scanning.scan_run
    ADD CONSTRAINT scan_run_purpose_check CHECK (purpose IN ('INTAKE', 'CLEAN_REPOSITORY', 'RESCAN', 'J7')),
    ADD COLUMN default_branch     TEXT CHECK (default_branch ~ '^[A-Za-z0-9._/-]{1,255}$'),
    ADD COLUMN default_branch_sha TEXT CHECK (default_branch_sha ~ '^[0-9a-f]{40}$');
