-- Job Posts (FR-12, FR-60). An Administrator can hide an Open Job Post (the hide command arrives with
-- FR-60), and a hidden one is left out of MEN-2 and MEN-3. The partial index serves the list of Open,
-- visible Job Posts, newest first; the Skill filter uses job_skill_idx (AD-38).

ALTER TABLE job.job
    ADD COLUMN hidden_at     TIMESTAMPTZ,
    ADD COLUMN hidden_reason TEXT CHECK (char_length(hidden_reason) BETWEEN 1 AND 500),
    ADD CONSTRAINT job_hidden_check CHECK ((hidden_at IS NULL) = (hidden_reason IS NULL));

CREATE INDEX job_open_post_idx ON job.job (opened_at DESC, id) WHERE state = 'OPEN' AND hidden_at IS NULL;
