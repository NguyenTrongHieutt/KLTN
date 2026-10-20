-- FR-59, AD-19: a lock that leaves a running Job unable to go on opens a Dispute by the System
-- (JobNeedsDecision), with no opener.

ALTER TABLE dispute.dispute DROP CONSTRAINT dispute_opened_by_check;

ALTER TABLE dispute.dispute
    ADD CONSTRAINT dispute_opened_by_check CHECK (opened_by IN ('CUSTOMER', 'MENTOR', 'SYSTEM')),
    ADD CONSTRAINT dispute_system_opener_check CHECK (opened_by <> 'SYSTEM' OR opener_account_id IS NULL);
