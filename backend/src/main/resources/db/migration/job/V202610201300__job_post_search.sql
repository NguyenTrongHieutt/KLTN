-- Keyword search over Job Posts (FR-12, AD-36, AD-38, SG-42). The vector holds the title, the guided
-- answers, the Job Types in the words Mentors read (vi.json term.jobType*.label), and the Skills'
-- current names, which JobSkillNames keeps fresh on SkillRenamed. platform.vi_search folds accents
-- and letter case, so the vector never calls unaccent() itself. Who may find a Job Post (approved
-- Mentors; Open, not hidden) is decided at query time.

-- IMMUTABLE because a generated column needs it; array_to_string is only STABLE in general, but
-- not for TEXT[] elements, which have no locale-dependent output.
CREATE FUNCTION job.post_search_text(title TEXT, problem TEXT, expected TEXT, app_users TEXT, done_check TEXT,
                                     job_types TEXT[], skill_names TEXT[]) RETURNS TEXT
    LANGUAGE sql
    IMMUTABLE
    PARALLEL SAFE
RETURN concat_ws(' ', title, problem, expected, app_users, done_check,
                 array_to_string(ARRAY(SELECT CASE type
                                                  WHEN 'ASSESSMENT' THEN 'Đánh giá'
                                                  WHEN 'BUG_FIX' THEN 'Sửa lỗi'
                                                  WHEN 'NEW_FEATURE' THEN 'Tính năng mới'
                                                  WHEN 'MODIFICATION' THEN 'Chỉnh sửa'
                                                  WHEN 'IMPROVEMENT' THEN 'Cải thiện'
                                                  WHEN 'SECURITY_TESTING' THEN 'Bảo mật và kiểm thử'
                                                  WHEN 'DEPLOYMENT_OPERATIONS' THEN 'Triển khai, vận hành và bảo trì'
                                                  WHEN 'DOCUMENTATION' THEN 'Tài liệu'
                                                  END
                                       FROM unnest(job_types) AS type), ' '),
                 array_to_string(skill_names, ' '));

GRANT EXECUTE ON FUNCTION job.post_search_text(TEXT, TEXT, TEXT, TEXT, TEXT, TEXT[], TEXT[]) TO ${runtime_role};

ALTER TABLE job.job
    ADD COLUMN search_vector tsvector GENERATED ALWAYS AS (to_tsvector('platform.vi_search',
        job.post_search_text(title, problem, expected, app_users, done_check, job_types, skill_names))) STORED;

CREATE INDEX job_search_idx ON job.job USING GIN (search_vector);
