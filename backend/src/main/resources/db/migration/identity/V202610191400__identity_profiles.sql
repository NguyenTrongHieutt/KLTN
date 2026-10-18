-- Profiles (FR-3, FR-4, FR-51), the theme preference (UX-DR5), and contact grants (FR-3, AD-3).
-- Personal data: exported, and deleted on erasure (AD-24).

-- A Customer's profile. The contact email is where notifications go once it is set (FR-48).
CREATE TABLE identity.customer_profile
(
    account_id    UUID PRIMARY KEY REFERENCES identity.account (id),
    kind          TEXT        NOT NULL CHECK (kind IN ('INDIVIDUAL', 'BUSINESS')),
    display_name  TEXT        NOT NULL CHECK (char_length(display_name) BETWEEN 1 AND 100),
    contact_email TEXT        NOT NULL CHECK (char_length(contact_email) BETWEEN 3 AND 254),
    phone         TEXT CHECK (phone ~ '^\+?[0-9 ]{8,20}$'),
    updated_at    TIMESTAMPTZ NOT NULL
);

GRANT SELECT, INSERT, UPDATE, DELETE ON identity.customer_profile TO ${runtime_role};

-- A Student's profile. Whether the real name and school appear publicly sits in the same row, and
-- starts hidden (FR-51, SG-42).
CREATE TABLE identity.student_profile
(
    account_id           UUID PRIMARY KEY REFERENCES identity.account (id),
    real_name            TEXT CHECK (char_length(real_name) <= 100),
    school               TEXT CHECK (char_length(school) <= 150),
    year_of_study        SMALLINT CHECK (year_of_study BETWEEN 1 AND 8),
    linkedin_url         TEXT CHECK (linkedin_url ~ '^https://www\.linkedin\.com/in/[A-Za-z0-9_%-]{3,100}/?$'),
    show_name_and_school BOOLEAN     NOT NULL DEFAULT FALSE,
    updated_at           TIMESTAMPTZ NOT NULL
);

GRANT SELECT, INSERT, UPDATE, DELETE ON identity.student_profile TO ${runtime_role};

-- Up to 5 public GitHub repositories a Student shows, as owner/name references: their code is never
-- fetched (FR-4).
CREATE TABLE identity.showcase_repository
(
    account_id UUID     NOT NULL REFERENCES identity.account (id),
    position   SMALLINT NOT NULL CHECK (position BETWEEN 0 AND 4),
    owner      TEXT     NOT NULL CHECK (owner ~ '^[A-Za-z0-9](?:[A-Za-z0-9-]{0,38})$'),
    name       TEXT     NOT NULL CHECK (name ~ '^[A-Za-z0-9._-]{1,100}$'),
    PRIMARY KEY (account_id, position),
    UNIQUE (account_id, owner, name)
);

GRANT SELECT, INSERT, UPDATE, DELETE ON identity.showcase_repository TO ${runtime_role};

-- The Mentor of a Customer's Job reads the Customer's contact details from Agreement until the Job
-- is Closed or Cancelled; job pushes the grant down and ends it (FR-3, AD-3). Never a Crew member.
CREATE TABLE identity.contact_grant
(
    customer_account_id UUID        NOT NULL REFERENCES identity.account (id),
    mentor_account_id   UUID        NOT NULL REFERENCES identity.account (id),
    job_id              UUID        NOT NULL,
    granted_at          TIMESTAMPTZ NOT NULL,
    ends_at             TIMESTAMPTZ,
    PRIMARY KEY (customer_account_id, mentor_account_id, job_id)
);

GRANT SELECT, INSERT, UPDATE, DELETE ON identity.contact_grant TO ${runtime_role};

-- "Sáng", "Tối" or "Theo hệ thống" (UX-DR5).
ALTER TABLE identity.account ADD COLUMN theme TEXT NOT NULL DEFAULT 'SYSTEM' CHECK (theme IN ('LIGHT', 'DARK', 'SYSTEM'));
