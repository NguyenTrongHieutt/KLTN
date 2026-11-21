-- Story 24.5a (AD-10, AD-12): the Source App has a webhook again, with its own secret, so its deliveries reach
-- POST /webhooks and are de-duplicated by X-GitHub-Delivery like the Platform App's. V202611141712 narrowed the
-- app CHECK of github.webhook_delivery to PLATFORM when the Merge App was retired; it widens to both Apps left.
-- It goes by its definition, whatever its name, as that migration's did. No row changes.
-- Personal data: none; a delivery ID, the App, and the event name identify nobody.

DO $$
DECLARE
    found RECORD;
BEGIN
    FOR found IN SELECT conname FROM pg_constraint
                 WHERE conrelid = 'github.webhook_delivery'::regclass AND contype = 'c'
                   AND pg_get_constraintdef(oid) LIKE '%PLATFORM%'
    LOOP
        EXECUTE format('ALTER TABLE github.webhook_delivery DROP CONSTRAINT %I', found.conname);
    END LOOP;
END $$;

ALTER TABLE github.webhook_delivery
    ADD CONSTRAINT webhook_delivery_app_check CHECK (app IN ('PLATFORM', 'SOURCE'));
