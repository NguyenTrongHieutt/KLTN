-- Story 23.9a (FR-37, R1, AD-7): the Final Merge's 2-day reminder told the Customer that an install request was
-- still waiting for an organization owner. There is no app to install at the end any more, so the Deadline has no
-- handler. Its rows go, pending or finished, and the poller never meets the kind. The FR-37 deadline itself
-- (repo.final_merge_deadline) stays: it still decides the closing choice.

DELETE FROM timers.deadline WHERE kind = 'repo.final_merge_reminder';
