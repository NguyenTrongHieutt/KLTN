-- FR-59, FR-82, AD-41 (Story 20.4): a lock counts as leaving, so the INACTIVE Crew status goes. A row a lock
-- left INACTIVE is released as a lock releases one now: LEFT when it was ever activated, its window ending where
-- it ended or now, and DECLINED when it never was, as a pick not yet signed. The status check then loses INACTIVE,
-- and the released reason check gains LEFT. The one-role triggers of V202610271100 keep their rule (a row that is
-- not released, or that was released after it was activated); the unreleased INACTIVE row its header mentions
-- no longer exists.
-- Personal data: unchanged; the rows keep naming the same accounts (JobPersonalData).

ALTER TABLE job.crew_row
    DROP CONSTRAINT crew_row_released_reason_check;

ALTER TABLE job.crew_row
    ADD CONSTRAINT crew_row_released_reason_check
        CHECK (released_reason IN ('DECLINED', 'REMOVED', 'JOB_ENDED', 'LEFT'));

UPDATE job.crew_row
SET status          = 'RELEASED',
    released_at     = now(),
    released_reason = 'LEFT',
    window_end      = coalesce(window_end, now())
WHERE status = 'INACTIVE'
  AND activated_at IS NOT NULL;

UPDATE job.crew_row
SET status          = 'RELEASED',
    released_at     = now(),
    released_reason = 'DECLINED'
WHERE status = 'INACTIVE'
  AND activated_at IS NULL;

ALTER TABLE job.crew_row
    DROP CONSTRAINT crew_row_status_check;

ALTER TABLE job.crew_row
    ADD CONSTRAINT crew_row_status_check CHECK (status IN ('ASSIGNED', 'ACTIVE', 'RELEASED'));
