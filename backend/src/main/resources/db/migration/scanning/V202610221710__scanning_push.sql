-- FR-8, AD-12, Story 7.4: a scan of a Working Repository after a push (purpose PUSH). Its Findings belong to
-- the Product like any other; repo pushes down their resolvers and tells the Job's parties.

ALTER TABLE scanning.scan_run DROP CONSTRAINT scan_run_purpose_check;

ALTER TABLE scanning.scan_run
    ADD CONSTRAINT scan_run_purpose_check CHECK (purpose IN ('INTAKE', 'CLEAN_REPOSITORY', 'RESCAN', 'J7', 'PUSH'));
