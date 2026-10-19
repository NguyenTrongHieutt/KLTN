-- AD-36: keyword search over public profile fields. A Student's vector holds their Skill names, and
-- their name and school only while they choose to show them (FR-51); a Mentor's holds the display
-- name, the experience description, and the Skill names. Who may be found (verified Students,
-- approved Mentors, both unlocked) is decided at query time.

ALTER TABLE identity.student_profile
    ADD COLUMN search_vector tsvector GENERATED ALWAYS AS (to_tsvector('platform.vi_search',
        skill_names || ' ' || CASE WHEN show_name_and_school
            THEN coalesce(real_name, '') || ' ' || coalesce(school, '') ELSE '' END)) STORED;

CREATE INDEX student_profile_search_idx ON identity.student_profile USING GIN (search_vector);

ALTER TABLE identity.mentor_application
    ADD COLUMN search_vector tsvector GENERATED ALWAYS AS (to_tsvector('platform.vi_search',
        coalesce(display_name, '') || ' ' || coalesce(experience, '') || ' ' || skill_names)) STORED;

CREATE INDEX mentor_application_search_idx ON identity.mentor_application USING GIN (search_vector);
