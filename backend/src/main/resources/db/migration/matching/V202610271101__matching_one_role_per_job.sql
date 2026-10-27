-- AD-43 (Story 20.2): one role per Job. A Customer never bids on their own Job, nor invites themselves to bid:
-- the bid and the invitation copy the Job's Customer, which never changes after the Job is created, and these
-- CHECKs keep the bidder or invitee apart from it. The commands refuse first with GUARD_FAILED ROLE_TAKEN; the
-- constraints are only the backstop.
-- Personal data: unchanged (MatchingPersonalData).

ALTER TABLE matching.application
    ADD CONSTRAINT application_not_own_job_check CHECK (mentor_account_id <> customer_account_id);

ALTER TABLE matching.invitation
    ADD CONSTRAINT invitation_not_own_job_check CHECK (mentor_account_id <> customer_account_id);
