-- Story 19.4 (FR-5, FR-80, AD-19, AD-34, AD-42, AD-24): every student verification is a row of
-- identity.verification, keyed by a keyed HMAC of its subject and never by the subject itself. The
-- school email address is stored nowhere: it lives in memory and in the email External Operation's
-- personal data, which is purged once the email completes. Any person's account may verify, after
-- N-26 (the VERIFY_STUDENT acknowledgment), declaring a name and a major that are then locked.
-- Existing verifications are not carried over: SQL cannot compute the keyed HMACs, the dev database
-- is reset before Epic 19, and production has none.

-- One row per verification. facts holds the public facts (school, name, major, verifiedMonth) while
-- the row is live; ending a row deletes them in the same transaction, and the subject HMAC stays as
-- the fraud-prevention basis N-26 states, until 24 months after the account's erasure (AD-24).
-- REVOKED is the Administrator's revocation, until Story 19.7 removes it.
-- Personal data: exported without the HMACs; erasure ends the live row ERASED.
CREATE TABLE identity.verification
(
    id              UUID PRIMARY KEY,
    account_id      UUID        NOT NULL REFERENCES identity.account (id),
    kind            TEXT        NOT NULL CHECK (kind IN ('STUDENT')),
    provider        TEXT        NOT NULL CHECK (provider IN ('EMAIL_CODE', 'STUDENT_CARD')),
    subject_hmac    TEXT        NOT NULL CHECK (subject_hmac ~ '^[0-9a-f]{64}$'),
    key_version     INT         NOT NULL CHECK (key_version > 0),
    facts           JSONB,
    consent_version TEXT CHECK (char_length(consent_version) BETWEEN 1 AND 20),
    verified_at     TIMESTAMPTZ NOT NULL,
    valid_until     DATE        NOT NULL,
    ended_at        TIMESTAMPTZ,
    ended_reason    TEXT CHECK (ended_reason IN ('EXPIRED', 'UNLINKED', 'REPLACED', 'ERASED', 'REVOKED')),
    CHECK ((ended_at IS NULL) = (ended_reason IS NULL)),
    CHECK ((ended_at IS NULL) = (facts IS NOT NULL)),
    CHECK (provider <> 'EMAIL_CODE' OR consent_version IS NOT NULL)
);

-- One live row per account and kind, and one live account per subject (STUDENT_EMAIL_IN_USE).
CREATE UNIQUE INDEX verification_one_live ON identity.verification (account_id, kind) WHERE ended_at IS NULL;
CREATE UNIQUE INDEX verification_subject_live ON identity.verification (kind, key_version, subject_hmac)
    WHERE ended_at IS NULL;
CREATE INDEX verification_account ON identity.verification (account_id, kind, verified_at);

GRANT SELECT, INSERT, UPDATE, DELETE ON identity.verification TO ${runtime_role};

ALTER TABLE identity.acknowledgment
    DROP CONSTRAINT acknowledgment_notice_check,
    ADD CONSTRAINT acknowledgment_notice_check
        CHECK (notice IN ('PRIVACY', 'COMMIT_EMAIL', 'LINK_GITHUB', 'VERIFY_STUDENT'));

-- A code is keyed by the address HMAC, never the address, and holds what the confirm writes: the
-- school matched from the allowlist and the declared name and major (a renewal's are the locked
-- ones). One code per account: it is deleted when used or replaced, and purged once expired.
DELETE FROM identity.school_email_code;
DROP INDEX identity.school_email_code_account;
ALTER TABLE identity.school_email_code
    DROP COLUMN email,
    DROP COLUMN replaced_at,
    DROP COLUMN consumed_at,
    ADD COLUMN address_hmac   TEXT    NOT NULL CHECK (address_hmac ~ '^[0-9a-f]{64}$'),
    ADD COLUMN key_version    INT     NOT NULL CHECK (key_version > 0),
    ADD COLUMN school         TEXT    NOT NULL CHECK (char_length(school) BETWEEN 3 AND 253),
    ADD COLUMN declared_name  TEXT    NOT NULL CHECK (char_length(declared_name) BETWEEN 1 AND 100),
    ADD COLUMN declared_major TEXT    NOT NULL CHECK (char_length(declared_major) BETWEEN 1 AND 100),
    ADD COLUMN renewal        BOOLEAN NOT NULL DEFAULT FALSE;
CREATE UNIQUE INDEX school_email_code_one_per_account ON identity.school_email_code (account_id);

-- The AD-34 limits count in their own rows: 10 sends per address HMAC and UTC day, and 3 per
-- account and clock hour; both are purged once their day or hour is over.
DELETE FROM identity.school_email_send_day;
ALTER TABLE identity.school_email_send_day RENAME COLUMN address_hash TO address_hmac;

CREATE TABLE identity.school_email_send_hour
(
    account_id UUID        NOT NULL REFERENCES identity.account (id),
    hour       TIMESTAMPTZ NOT NULL,
    sends      INTEGER     NOT NULL CHECK (sends >= 1),
    PRIMARY KEY (account_id, hour)
);

GRANT SELECT, INSERT, UPDATE, DELETE ON identity.school_email_send_hour TO ${runtime_role};

-- The profile holds the live verification's facts as its projection, which the AD-36 search column
-- reads; the details typed before this story are cleared, since nothing verified them.
ALTER TABLE identity.profile
    ADD COLUMN student_major TEXT CHECK (char_length(student_major) BETWEEN 1 AND 100),
    DROP CONSTRAINT profile_student_school_check,
    ADD CONSTRAINT profile_student_school_check CHECK (char_length(student_school) BETWEEN 1 AND 253);
UPDATE identity.profile SET student_name = NULL, student_school = NULL, student_year = NULL;

DROP TABLE identity.student_verification;
