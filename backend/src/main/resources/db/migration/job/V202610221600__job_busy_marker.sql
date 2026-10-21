-- AD-28, AD-5: the busy marker. A precondition operation (Setup, Verify, an acceptance or settlement
-- merge, the Final Merge) reserves the Job with its operation key, purpose, and subject before its effect
-- runs; its resolving transaction always clears it. While it is set, other J and M transitions, a Change
-- Request taking effect, and new Payment Orders are refused with JOB_BUSY. It is not part of Job.version.

ALTER TABLE job.job
    ADD COLUMN busy_operation_key TEXT CHECK (length(busy_operation_key) BETWEEN 1 AND 200),
    ADD COLUMN busy_purpose       TEXT CHECK (busy_purpose IN ('ACCEPTANCE', 'SETTLEMENT', 'FINAL_MERGE', 'JOB_SETUP',
        'JOB_VERIFY')),
    ADD COLUMN busy_subject       TEXT CHECK (length(busy_subject) BETWEEN 1 AND 200),
    ADD COLUMN busy_since         TIMESTAMPTZ,
    ADD CONSTRAINT job_busy_check CHECK ((busy_operation_key IS NULL) = (busy_purpose IS NULL)
        AND (busy_operation_key IS NULL) = (busy_since IS NULL)
        AND (busy_subject IS NULL OR busy_operation_key IS NOT NULL));
