-- Student verification by a school email code (FR-5, AD-34). Personal data: exported, and deleted on
-- erasure (AD-24); the codes themselves are kept only as hashes.

-- One code per send. A new send replaces the pending one; a correct code is consumed. The rows also
-- count the sends per account and hour, and are purged a day after they were sent.
CREATE TABLE identity.school_email_code
(
    id          UUID PRIMARY KEY,
    account_id  UUID        NOT NULL REFERENCES identity.account (id),
    email       TEXT        NOT NULL CHECK (char_length(email) BETWEEN 3 AND 254),
    code_hash   TEXT        NOT NULL CHECK (code_hash ~ '^[0-9a-f]{64}$'),
    sent_at     TIMESTAMPTZ NOT NULL,
    expires_at  TIMESTAMPTZ NOT NULL,
    attempts    SMALLINT    NOT NULL DEFAULT 0 CHECK (attempts BETWEEN 0 AND 5),
    replaced_at TIMESTAMPTZ,
    consumed_at TIMESTAMPTZ
);

CREATE INDEX school_email_code_account ON identity.school_email_code (account_id, sent_at);

GRANT SELECT, INSERT, UPDATE, DELETE ON identity.school_email_code TO ${runtime_role};

-- Sends per school address and UTC day (AD-34), keyed by the SHA-256 of the lower-case address so
-- the counter holds no address; purged after the day.
CREATE TABLE identity.school_email_send_day
(
    address_hash TEXT    NOT NULL CHECK (address_hash ~ '^[0-9a-f]{64}$'),
    day          DATE    NOT NULL,
    sends        INTEGER NOT NULL CHECK (sends >= 1),
    PRIMARY KEY (address_hash, day)
);

GRANT SELECT, INSERT, UPDATE, DELETE ON identity.school_email_send_day TO ${runtime_role};

-- A Student's verification: until valid_until (the next 30 September), inclusive. A school email
-- verifies at most one account (FR-5).
CREATE TABLE identity.student_verification
(
    account_id   UUID PRIMARY KEY REFERENCES identity.account (id),
    method       TEXT        NOT NULL CHECK (method IN ('SCHOOL_EMAIL')),
    school_email TEXT UNIQUE CHECK (char_length(school_email) BETWEEN 3 AND 254),
    verified_at  TIMESTAMPTZ NOT NULL,
    valid_until  DATE        NOT NULL,
    CHECK (method <> 'SCHOOL_EMAIL' OR school_email IS NOT NULL)
);

GRANT SELECT, INSERT, UPDATE, DELETE ON identity.student_verification TO ${runtime_role};
