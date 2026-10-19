-- FR-65: the Personal-Data Check's Findings beside the credential ones. A personal-data Finding is
-- one file; its preview holds up to two samples, each masked with bullets (the check below insists),
-- and match_count the lines holding a personal value.

ALTER TABLE scanning.finding
    ADD COLUMN category TEXT NOT NULL DEFAULT 'CREDENTIAL' CHECK (category IN ('CREDENTIAL', 'PERSONAL_DATA'));

ALTER TABLE scanning.finding ALTER COLUMN category DROP DEFAULT;

ALTER TABLE scanning.finding
    ADD COLUMN match_count INTEGER CHECK (match_count > 0);

ALTER TABLE scanning.finding DROP CONSTRAINT finding_masked_preview_check;

ALTER TABLE scanning.finding
    ADD CONSTRAINT finding_masked_preview_check CHECK (
        (category = 'CREDENTIAL' AND char_length(masked_preview) BETWEEN 8 AND 16 AND match_count IS NULL)
        OR (category = 'PERSONAL_DATA' AND char_length(masked_preview) BETWEEN 1 AND 200
            AND strpos(masked_preview, '•') > 0 AND match_count IS NOT NULL));
