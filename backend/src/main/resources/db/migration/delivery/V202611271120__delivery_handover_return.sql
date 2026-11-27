-- FR-86, AD-47 exception 2 (Story 25.5): the Customer of a Self-mentored Job may return a Submitted Milestone's
-- handover with a written reason. The handover is void (M3), so it is VOIDED with its voided_at as every voided
-- handover is; these two columns say that a person returned it, and why. A handover voided because the acceptance
-- merge answered 409 has neither.
-- Personal data: returned_by_account_id is the Customer's account, cleared when the account is erased; return_reason
-- is the Customer's own words, read by the Job's members and kept with the handover as its criteria and checks are.
-- The mentor_account_id column is unchanged and now names whoever handed over: the Mentor, or in a Self-mentored Job
-- the active Crew member (FR-32, AD-47 exception 1).

ALTER TABLE delivery.handover
    ADD COLUMN returned_by_account_id UUID,
    ADD COLUMN return_reason TEXT,
    ADD CONSTRAINT handover_return_check CHECK (
        (return_reason IS NULL OR (status = 'VOIDED' AND length(btrim(return_reason)) BETWEEN 1 AND 2000))
        AND (returned_by_account_id IS NULL OR return_reason IS NOT NULL));
