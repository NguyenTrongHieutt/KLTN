-- Story 21.3 (FR-14, AD-36, SG-41, X9): "Tìm người hướng dẫn" searches the public fields of GitHub-verified, unlocked
-- users: the display name, the experience, the Skill names, and the student name declared at verification only
-- while its owner shows it. The school and the major stay on PUB-4 and are no longer search terms.

DROP INDEX identity.profile_search_idx;
ALTER TABLE identity.profile DROP COLUMN search_vector;
ALTER TABLE identity.profile
    ADD COLUMN search_vector tsvector GENERATED ALWAYS AS (to_tsvector('platform.vi_search',
        coalesce(display_name, '') || ' ' || coalesce(experience, '') || ' ' || skill_names || ' '
            || CASE WHEN hide_student_details THEN '' ELSE coalesce(student_name, '') END)) STORED;
CREATE INDEX profile_search_idx ON identity.profile USING GIN (search_vector);
