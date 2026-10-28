-- FR-12, FR-81, AD-3, AD-36, AD-41 (Story 21.1): the Mentor's Crew recruitment setting lives on the Job, so that
-- job.phaseOf and "Tìm việc" never read matching. recruiting is the switch (off by default) and recruiting_since
-- when it last turned on; open_slots counts the places still open (at most 5 unreleased rows, the Crew cap of
-- AD-41); recruit_skill_ids and recruit_skill_names are the Skills sought, renamed with the Skill as skill_names
-- are (AD-38, JobSkillNames); recruit_note is the Mentor's note. Story 21.4 adds the Mentor's commands; until then
-- only fixtures write these columns. A listed Job's keyword search also covers the note and the Skills sought while
-- recruitment is on, so the generated search_vector is dropped and added again over the new text.
-- Personal data: the note is the Mentor's text on the Customer's Job and is deleted with the Job (JobPersonalData);
-- no export covers it yet: the Mentor's export of it comes with Story 21.4, which writes it.

ALTER TABLE job.job
    ADD COLUMN recruiting          BOOLEAN NOT NULL DEFAULT false,
    ADD COLUMN recruiting_since    TIMESTAMPTZ,
    ADD COLUMN open_slots          INTEGER NOT NULL DEFAULT 0 CHECK (open_slots BETWEEN 0 AND 5),
    ADD COLUMN recruit_skill_ids   UUID[]  NOT NULL DEFAULT '{}',
    ADD COLUMN recruit_skill_names TEXT[]  NOT NULL DEFAULT '{}',
    ADD COLUMN recruit_note        TEXT CHECK (char_length(recruit_note) BETWEEN 1 AND 1000),
    ADD CONSTRAINT job_recruit_skills_check
        CHECK (cardinality(recruit_skill_ids) <= 10 AND cardinality(recruit_skill_ids) = cardinality(recruit_skill_names)),
    ADD CONSTRAINT job_recruiting_since_check CHECK (NOT recruiting OR recruiting_since IS NOT NULL);

-- SkillRenamed finds the Jobs seeking a Skill.
CREATE INDEX job_recruit_skill_idx ON job.job USING GIN (recruit_skill_ids);

-- The Job Post's text as before, then, while recruitment is on, the note and the Skills sought. IMMUTABLE, as the
-- generated column needs; the earlier function stays and builds the Job Post's part.
CREATE FUNCTION job.post_search_text(title TEXT, problem TEXT, expected TEXT, app_users TEXT, done_check TEXT,
                                     job_types TEXT[], skill_names TEXT[], recruiting BOOLEAN, recruit_note TEXT,
                                     recruit_skill_names TEXT[]) RETURNS TEXT
    LANGUAGE sql
    IMMUTABLE
    PARALLEL SAFE
RETURN concat_ws(' ', job.post_search_text(title, problem, expected, app_users, done_check, job_types, skill_names),
                 CASE WHEN recruiting THEN concat_ws(' ', recruit_note, array_to_string(recruit_skill_names, ' ')) END);

GRANT EXECUTE ON FUNCTION job.post_search_text(TEXT, TEXT, TEXT, TEXT, TEXT, TEXT[], TEXT[], BOOLEAN, TEXT, TEXT[])
    TO ${runtime_role};

-- Dropping the generated column drops job_search_idx with it.
ALTER TABLE job.job
    DROP COLUMN search_vector;

ALTER TABLE job.job
    ADD COLUMN search_vector tsvector GENERATED ALWAYS AS (to_tsvector('platform.vi_search',
        job.post_search_text(title, problem, expected, app_users, done_check, job_types, skill_names, recruiting,
                             recruit_note, recruit_skill_names))) STORED;

CREATE INDEX job_search_idx ON job.job USING GIN (search_vector);
