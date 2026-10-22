-- FR-53, AD-20, AD-31 (Story 15.2): the read grant pushed down to chat and work when an Administrator
-- takes a Dispute, ended when another takes it over and 30 days after the decision. A Dispute taken before
-- this story has no grant until its assignee takes it again.
-- Personal data: none (an ID).

ALTER TABLE dispute.dispute
    ADD COLUMN grant_id UUID;

GRANT UPDATE (grant_id) ON dispute.dispute TO ${runtime_role};
