-- AD-19, A-17 (Story 22.3): an Administrator's email address becomes a person-link signal when they are created, so taking
-- a case they are linked to is refused (CONFLICT_OF_INTEREST). It has the kind EMAIL, since the check joins on the kind.
-- Administrators created before this have no such row: an idempotent startup step records theirs, because the HMAC key
-- is a secret file SQL cannot read (AdminEmailSignals).
-- Personal data: an HMAC, no address, under the signals' retention (AD-24).

ALTER TABLE identity.person_signal
    DROP CONSTRAINT person_signal_source_check;

ALTER TABLE identity.person_signal
    ADD CONSTRAINT person_signal_source_check
        CHECK (source IN ('GOOGLE_EMAIL', 'GITHUB_EMAIL', 'SCHOOL_EMAIL', 'LINKEDIN', 'ADMIN_EMAIL'));
