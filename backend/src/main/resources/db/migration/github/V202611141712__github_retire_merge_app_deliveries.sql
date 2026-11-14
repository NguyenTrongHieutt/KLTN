-- Story 23.9b (R1, R16, AD-10, AD-12): the Merge App is retired, and only the Platform App has a webhook. The
-- deduplication history of github.webhook_delivery still named the retired App in its column CHECK, so the live
-- schema kept it and a stale MERGE row stayed valid. The rows go first: the table is 30-day history that only
-- decides whether a delivery was seen before, and no delivery of the retired App can arrive again. Then the CHECK
-- narrows to the one App left. It goes by its definition, whatever its name, as Story 23.9a's migrations did.
-- Personal data: none; a delivery ID, the App, and the event name identify nobody.

DELETE FROM github.webhook_delivery WHERE app = 'MERGE';

DO $$
DECLARE
    found RECORD;
BEGIN
    FOR found IN SELECT conname FROM pg_constraint
                 WHERE conrelid = 'github.webhook_delivery'::regclass AND contype = 'c'
                   AND pg_get_constraintdef(oid) LIKE '%MERGE%'
    LOOP
        EXECUTE format('ALTER TABLE github.webhook_delivery DROP CONSTRAINT %I', found.conname);
    END LOOP;
END $$;

ALTER TABLE github.webhook_delivery
    ADD CONSTRAINT webhook_delivery_app_check CHECK (app IN ('PLATFORM'));
