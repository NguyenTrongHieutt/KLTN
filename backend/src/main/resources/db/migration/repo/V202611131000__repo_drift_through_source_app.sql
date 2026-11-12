-- Story 23.5b (FR-20, AD-12, R7): the daily drift poll reads the Source Repository through the Customer's Source
-- App, so "made private" is no longer drift and DELETED_OR_PRIVATE goes. Through the Source App a deleted repository
-- cannot be told from one removed from the App's selection or transferred to an account without the App, so each is
-- GONE; the Source App uninstalled (APP_UNINSTALLED) and vibefix.yml removed or changed (WORKFLOW_CHANGED) are new
-- kinds. First the kind CHECK that names the retired kind goes, found by its definition whatever its name; then the
-- old rows map to GONE; then the new kind CHECK is added.

DO $$
DECLARE
    found RECORD;
BEGIN
    FOR found IN SELECT conname FROM pg_constraint
                 WHERE conrelid = 'repo.drift_warning'::regclass AND contype = 'c'
                   AND pg_get_constraintdef(oid) LIKE '%DELETED_OR_PRIVATE%'
    LOOP
        EXECUTE format('ALTER TABLE repo.drift_warning DROP CONSTRAINT %I', found.conname);
    END LOOP;
END $$;

UPDATE repo.drift_warning SET kind = 'GONE' WHERE kind = 'DELETED_OR_PRIVATE';

ALTER TABLE repo.drift_warning
    ADD CONSTRAINT drift_warning_kind_check CHECK (kind IN ('COMMITS', 'GONE', 'ARCHIVED', 'RENAMED', 'TRANSFERRED',
        'APP_UNINSTALLED', 'WORKFLOW_CHANGED'));
