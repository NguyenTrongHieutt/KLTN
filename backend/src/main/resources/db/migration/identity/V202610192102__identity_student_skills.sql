-- FR-4, AD-38: a Student's Skills, as catalog Skill IDs (no cross-schema key, AD-2) with a GIN index
-- for the Skill filters, and their names for search, rewritten from catalog on every save and on
-- each SkillRenamed. At most 20 (FR-4); inactive IDs already chosen may stay.
ALTER TABLE identity.student_profile
    ADD COLUMN skill_ids   UUID[] NOT NULL DEFAULT '{}' CHECK (cardinality(skill_ids) <= 20),
    ADD COLUMN skill_names TEXT   NOT NULL DEFAULT '' CHECK (char_length(skill_names) <= 2000);

CREATE INDEX student_profile_skills_idx ON identity.student_profile USING GIN (skill_ids);
