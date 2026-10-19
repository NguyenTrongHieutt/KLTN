-- The Mentor application, which is also the approved Mentor's profile (FR-74). One row per Mentor;
-- a row without submitted_at is a draft that holds only a document uploaded before the first
-- submission. The LinkedIn link is reserved by a live application (AD-19): its HMAC is unique among
-- reserved rows. The document's bytes are in files (AD-21); its name, type, and size are kept
-- here for MEN-5 and ADM-13. Personal data: exported, and deleted on erasure (AD-24).
CREATE TABLE identity.mentor_application
(
    account_id             UUID PRIMARY KEY REFERENCES identity.account (id),
    display_name           TEXT CHECK (char_length(display_name) BETWEEN 1 AND 100),
    experience             TEXT CHECK (char_length(experience) BETWEEN 1 AND 4000),
    years_of_experience    SMALLINT CHECK (years_of_experience BETWEEN 0 AND 60),
    skill_ids              UUID[]      NOT NULL DEFAULT '{}' CHECK (cardinality(skill_ids) <= 20),
    skill_names            TEXT        NOT NULL DEFAULT '' CHECK (char_length(skill_names) <= 2000),
    linkedin_url           TEXT CHECK (linkedin_url ~ '^https://www\.linkedin\.com/in/[A-Za-z0-9_%-]{3,100}/?$'),
    linkedin_hmac          TEXT CHECK (linkedin_hmac ~ '^[0-9a-f]{64}$'),
    linkedin_key_version   INTEGER CHECK (linkedin_key_version > 0),
    linkedin_reserved      BOOLEAN     NOT NULL DEFAULT FALSE,
    other_link             TEXT CHECK (char_length(other_link) <= 300 AND other_link ~ '^https://'),
    document_file_id       UUID UNIQUE,
    document_name          TEXT CHECK (char_length(document_name) BETWEEN 1 AND 200),
    document_media_type    TEXT CHECK (char_length(document_media_type) BETWEEN 3 AND 100),
    document_size          BIGINT CHECK (document_size > 0),
    privacy_notice_version TEXT CHECK (char_length(privacy_notice_version) BETWEEN 1 AND 20),
    n22_version            TEXT CHECK (char_length(n22_version) BETWEEN 1 AND 20),
    submitted_at           TIMESTAMPTZ,
    updated_at             TIMESTAMPTZ NOT NULL,
    CHECK (submitted_at IS NULL OR (display_name IS NOT NULL AND experience IS NOT NULL
        AND years_of_experience IS NOT NULL AND cardinality(skill_ids) >= 1 AND linkedin_url IS NOT NULL
        AND linkedin_hmac IS NOT NULL AND privacy_notice_version IS NOT NULL AND n22_version IS NOT NULL)),
    CHECK ((linkedin_hmac IS NULL) = (linkedin_key_version IS NULL)),
    CHECK (NOT linkedin_reserved OR linkedin_hmac IS NOT NULL),
    CHECK ((document_file_id IS NULL) = (document_name IS NULL)
        AND (document_file_id IS NULL) = (document_media_type IS NULL)
        AND (document_file_id IS NULL) = (document_size IS NULL))
);

-- MENTOR_LINKEDIN_IN_USE (AD-19): one live application per LinkedIn profile.
CREATE UNIQUE INDEX mentor_application_linkedin_idx ON identity.mentor_application (linkedin_hmac)
    WHERE linkedin_reserved;
CREATE INDEX mentor_application_skills_idx ON identity.mentor_application USING GIN (skill_ids);
CREATE INDEX mentor_application_submitted_idx ON identity.mentor_application (submitted_at);

GRANT SELECT, INSERT, UPDATE, DELETE ON identity.mentor_application TO ${runtime_role};
