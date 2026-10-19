-- J3 (FR-15, AD-30): the Customer selects an Application, and the Job records its Mentor, which never
-- changes after, with the Application and the time of the selection. A Job past Open always has its
-- Mentor. The Mentor's running Jobs count against the FR-13 limit (job.activeJobCount, AD-33).
-- Personal data: the Mentor's ID stays with the Job's records (JobPersonalData).

ALTER TABLE job.job
    ADD COLUMN mentor_account_id       UUID,
    ADD COLUMN selected_application_id UUID,
    ADD COLUMN selected_at             TIMESTAMPTZ,
    ADD CONSTRAINT job_mentor_check CHECK (state IN ('DRAFT', 'OPEN')
        OR (mentor_account_id IS NOT NULL AND selected_application_id IS NOT NULL AND selected_at IS NOT NULL));

CREATE INDEX job_mentor_idx ON job.job (mentor_account_id, state) WHERE mentor_account_id IS NOT NULL;
