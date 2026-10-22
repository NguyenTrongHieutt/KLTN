-- FR-36, AD-14 (Story 14.2): a request that raises a Held Milestone's price waits for the Customer's top-up
-- (AWAITING_TOP_UP) after the Mentor's confirmation, with the difference and its Milestone; it is still the
-- Job's open request and takes effect when the top-up's IPN succeeds.

DO $$
DECLARE
    found RECORD;
BEGIN
    FOR found IN SELECT conname FROM pg_constraint
                 WHERE conrelid = 'job.change_request'::regclass AND contype = 'c'
                   AND (conname = 'change_request_status_check' OR pg_get_constraintdef(oid) LIKE '%closed_at IS NULL%')
    LOOP
        EXECUTE format('ALTER TABLE job.change_request DROP CONSTRAINT %I', found.conname);
    END LOOP;
END $$;

ALTER TABLE job.change_request
    ADD COLUMN top_up_vnd          BIGINT CHECK (top_up_vnd > 0),
    ADD COLUMN top_up_milestone_id UUID,
    ADD CONSTRAINT change_request_status_check
        CHECK (status IN ('OPEN', 'AWAITING_TOP_UP', 'APPLIED', 'LAPSED', 'WITHDRAWN')),
    ADD CONSTRAINT change_request_open_check CHECK ((status IN ('OPEN', 'AWAITING_TOP_UP')) = (closed_at IS NULL)),
    ADD CONSTRAINT change_request_top_up_check CHECK ((top_up_vnd IS NULL) = (top_up_milestone_id IS NULL)),
    ADD CONSTRAINT change_request_waiting_check
        CHECK (status <> 'AWAITING_TOP_UP' OR (top_up_vnd IS NOT NULL AND mentor_confirmed_at IS NOT NULL));

DROP INDEX job.change_request_open_idx;

CREATE UNIQUE INDEX change_request_open_idx ON job.change_request (job_id) WHERE status IN ('OPEN', 'AWAITING_TOP_UP');

GRANT UPDATE (top_up_vnd, top_up_milestone_id) ON job.change_request TO ${runtime_role};
