-- FR-39: a person's closure proposal not answered within the copied days tells its proposer, who may
-- then open a Dispute; the proposal records when that happened.

ALTER TABLE job.closure_proposal
    ADD COLUMN unanswered_at TIMESTAMPTZ;

GRANT UPDATE (unanswered_at) ON job.closure_proposal TO ${runtime_role};
