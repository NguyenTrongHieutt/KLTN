-- FR-86, AD-47 exception 2 (Story 25.5): the Customer of a Self-mentored Job may return a Submitted Milestone's
-- handover with a written reason. The handover is void (M3), so it is VOIDED with its voided_at as every voided
-- handover is; "returned" says that a person returned it, and stays for the Milestone history. A handover voided
-- because the acceptance merge answered 409 is not returned.
-- Personal data: returned_by_account_id is the Customer's account, cleared when the account is erased; return_reason
-- is the Customer's own words, read by the Job's members. It is what the handover said, like its criterion notes and
-- the Customer's comments, so the NFR-10 retirement clears it with them and leaves the "returned" mark.
-- The mentor_account_id column is unchanged and now names whoever handed over: the Mentor, or in a Self-mentored Job
-- the active Crew member (FR-32, AD-47 exception 1).

ALTER TABLE delivery.handover
    ADD COLUMN returned BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN returned_by_account_id UUID,
    ADD COLUMN return_reason TEXT,
    ADD CONSTRAINT handover_return_check CHECK (
        (NOT returned OR status = 'VOIDED')
        AND (returned OR (returned_by_account_id IS NULL AND return_reason IS NULL))
        AND (return_reason IS NULL OR length(btrim(return_reason)) BETWEEN 1 AND 2000));

CREATE INDEX handover_returner_idx ON delivery.handover (returned_by_account_id) WHERE returned_by_account_id IS NOT NULL;

-- The runtime role writes the handover's columns one grant at a time (the row is evidence): the return's three. Erasure
-- clears the account, and retirement the reason.
GRANT UPDATE (returned, returned_by_account_id, return_reason) ON delivery.handover TO ${runtime_role};
