-- FR-40's stall deadlines: 14 days in Agreement or 7 in Starting let either side cancel alone, with a
-- full refund of held money. The Job records when a deadline passed; a one-sided cancellation is kept
-- as a confirmed proposal marked alone.

ALTER TABLE job.job
    ADD COLUMN stall_deadline_passed_at TIMESTAMPTZ;

ALTER TABLE job.cancellation_proposal
    ADD COLUMN alone BOOLEAN NOT NULL DEFAULT false;
