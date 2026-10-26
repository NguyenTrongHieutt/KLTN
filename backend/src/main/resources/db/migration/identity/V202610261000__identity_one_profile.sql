-- Story 19.3 (FR-3, FR-4, FR-51, FR-72, AD-36, AD-38, AD-42): one profile per account, whatever role
-- the person holds in a Job. It is what the person typed ("Bạn tự nhập"); nothing in it is verified,
-- and no profile endpoint writes a verified fact. The row is created on the first save.
-- display_name is required by every save; the rows carried over from a Student's profile have none.
-- The student details are the ones a Student typed before Story 19.4, which declares them at
-- verification; they stay public unless hide_student_details is set (X9), and the search vector holds
-- them only while they are shown (SG-42). Skills are catalog IDs with their names for search, rewritten
-- on each SkillRenamed. Typed links are "Tự khai" and record no person-link signal.
-- Personal data: exported, and deleted on erasure (AD-24).

CREATE TABLE identity.profile
(
    account_id           UUID PRIMARY KEY REFERENCES identity.account (id),
    display_name         TEXT CHECK (char_length(display_name) BETWEEN 1 AND 100),
    kind                 TEXT        NOT NULL DEFAULT 'INDIVIDUAL' CHECK (kind IN ('INDIVIDUAL', 'BUSINESS')),
    contact_email        TEXT CHECK (char_length(contact_email) BETWEEN 3 AND 254),
    phone                TEXT CHECK (phone ~ '^\+?[0-9 ]{8,20}$'),
    experience           TEXT CHECK (char_length(experience) BETWEEN 1 AND 2000),
    years_of_experience  SMALLINT CHECK (years_of_experience BETWEEN 0 AND 60),
    skill_ids            UUID[]      NOT NULL DEFAULT '{}' CHECK (cardinality(skill_ids) <= 20),
    skill_names          TEXT        NOT NULL DEFAULT '' CHECK (char_length(skill_names) <= 2000),
    student_name         TEXT CHECK (char_length(student_name) BETWEEN 1 AND 100),
    student_school       TEXT CHECK (char_length(student_school) BETWEEN 1 AND 150),
    student_year         SMALLINT CHECK (student_year BETWEEN 1 AND 8),
    hide_student_details BOOLEAN     NOT NULL DEFAULT FALSE,
    updated_at           TIMESTAMPTZ NOT NULL,
    search_vector        tsvector GENERATED ALWAYS AS (to_tsvector('platform.vi_search',
        coalesce(display_name, '') || ' ' || coalesce(experience, '') || ' ' || skill_names || ' '
            || CASE WHEN hide_student_details THEN ''
                    ELSE coalesce(student_name, '') || ' ' || coalesce(student_school, '') END)) STORED
);

CREATE INDEX profile_skills_idx ON identity.profile USING GIN (skill_ids);
CREATE INDEX profile_search_idx ON identity.profile USING GIN (search_vector);

GRANT SELECT, INSERT, UPDATE, DELETE ON identity.profile TO ${runtime_role};

-- Up to 5 links the person typed, https only, each shown with "Tự khai".
CREATE TABLE identity.profile_link
(
    account_id UUID     NOT NULL REFERENCES identity.account (id),
    position   SMALLINT NOT NULL CHECK (position BETWEEN 0 AND 4),
    url        TEXT     NOT NULL CHECK (char_length(url) BETWEEN 9 AND 500 AND url ~ '^https://[^\s]+$'),
    PRIMARY KEY (account_id, position),
    UNIQUE (account_id, url)
);

GRANT SELECT, INSERT, UPDATE, DELETE ON identity.profile_link TO ${runtime_role};

-- The three role profiles become one row per account.
INSERT INTO identity.profile (account_id, display_name, kind, contact_email, phone, updated_at)
SELECT account_id, display_name, kind, contact_email, phone, updated_at
FROM identity.customer_profile;

INSERT INTO identity.profile
    (account_id, skill_ids, skill_names, student_name, student_school, student_year, hide_student_details, updated_at)
SELECT account_id, skill_ids, skill_names, nullif(btrim(real_name), ''), nullif(btrim(school), ''), year_of_study,
       NOT show_name_and_school, updated_at
FROM identity.student_profile
ON CONFLICT (account_id) DO UPDATE SET
    skill_ids = EXCLUDED.skill_ids,
    skill_names = EXCLUDED.skill_names,
    student_name = EXCLUDED.student_name,
    student_school = EXCLUDED.student_school,
    student_year = EXCLUDED.student_year,
    hide_student_details = EXCLUDED.hide_student_details,
    updated_at = greatest(identity.profile.updated_at, EXCLUDED.updated_at);

-- A draft application holds only a document, so it brings no profile fields.
INSERT INTO identity.profile (account_id, display_name, experience, years_of_experience, skill_ids, skill_names, updated_at)
SELECT account_id, display_name, left(experience, 2000), years_of_experience, skill_ids, skill_names, updated_at
FROM identity.mentor_application
WHERE display_name IS NOT NULL OR experience IS NOT NULL OR years_of_experience IS NOT NULL
   OR cardinality(skill_ids) > 0
ON CONFLICT (account_id) DO UPDATE SET
    display_name = coalesce(identity.profile.display_name, EXCLUDED.display_name),
    experience = EXCLUDED.experience,
    years_of_experience = EXCLUDED.years_of_experience,
    skill_ids = CASE WHEN cardinality(identity.profile.skill_ids) = 0 THEN EXCLUDED.skill_ids
                     ELSE identity.profile.skill_ids END,
    skill_names = CASE WHEN cardinality(identity.profile.skill_ids) = 0 THEN EXCLUDED.skill_names
                       ELSE identity.profile.skill_names END,
    updated_at = greatest(identity.profile.updated_at, EXCLUDED.updated_at);

-- A Student's typed LinkedIn link stays as their first typed link, "Tự khai" like any other.
INSERT INTO identity.profile_link (account_id, position, url)
SELECT account_id, 0, linkedin_url
FROM identity.student_profile
WHERE linkedin_url IS NOT NULL;

DROP TABLE identity.customer_profile;
DROP TABLE identity.student_profile;

-- The Mentor application keeps only what is the application's own until Story 19.7: the LinkedIn link,
-- the other link, the document, the notices and the submission.
ALTER TABLE identity.mentor_application DROP COLUMN search_vector;

DO $$
DECLARE
    found RECORD;
BEGIN
    FOR found IN SELECT conname FROM pg_constraint
                 WHERE conrelid = 'identity.mentor_application'::regclass AND contype = 'c'
                   AND pg_get_constraintdef(oid) LIKE '%submitted_at IS NULL%'
    LOOP
        EXECUTE format('ALTER TABLE identity.mentor_application DROP CONSTRAINT %I', found.conname);
    END LOOP;
END $$;

ALTER TABLE identity.mentor_application
    DROP COLUMN display_name,
    DROP COLUMN experience,
    DROP COLUMN years_of_experience,
    DROP COLUMN skill_ids,
    DROP COLUMN skill_names,
    ADD CONSTRAINT mentor_application_submitted_check CHECK (submitted_at IS NULL OR (linkedin_url IS NOT NULL
        AND linkedin_hmac IS NOT NULL AND privacy_notice_version IS NOT NULL AND n22_version IS NOT NULL));
