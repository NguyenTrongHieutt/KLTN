-- Story 24.2b (D1, FR-50, AD-33, AD-38): the Board is retired, so a Portfolio Entry can hold no fact read from it.
-- A Crew member's facts become the commits they authored in the Job's accepted Milestone Handovers, read from
-- delivery's records and matched to the person by the GitHub numeric ID their profile carries (AD-10); the Mentor's
-- stay the Milestones they handed over. No entry names a Work Item or a review (FR-50, AD-33). V202610211600 is
-- superseded in these three columns; it stays as Flyway history.
--
-- What this loses: work_items_approved, a Crew member's Work Items that reached "Xong" through the Mentor's
-- approval, and reviews_written, how many reviews (approvals and returns) the Mentor wrote on the Board. Both were
-- counted from work.work_item_history, which this story drops, so neither can be recomputed and neither can be
-- kept. Entries frozen before this story keep their milestones_accepted, their Job Types, their Skills and their
-- summary; a Crew member's commits_authored reads NULL there, which the entry's own screens draw as nothing rather
-- than as zero, because vibefix never counted that Job's commits.
--
-- The two unnamed CHECK constraints that tied the old columns to the role go with them (PostgreSQL drops a table
-- constraint that involves a dropped column), so both are written again over the columns that remain: a CREW entry
-- carries a commit count and no Milestones handed over, a MENTOR entry the other way round.
-- Personal data: one column added, two deleted, all three facts frozen from records and never typed by anyone; the
-- entry stays exported for its person and deleted on their erasure, with one fact fewer.

ALTER TABLE reputation.portfolio_entry
    DROP COLUMN work_items_approved,
    DROP COLUMN reviews_written,
    ADD COLUMN commits_authored INTEGER CHECK (commits_authored >= 0);

ALTER TABLE reputation.portfolio_entry
    ADD CONSTRAINT portfolio_entry_crew_facts CHECK ((role = 'CREW') = (commits_authored IS NOT NULL)) NOT VALID,
    ADD CONSTRAINT portfolio_entry_mentor_facts CHECK ((role = 'MENTOR') = (milestones_handed_over IS NOT NULL)) NOT VALID;
