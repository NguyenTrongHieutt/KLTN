-- Story 24.2b (D1, FR-50, AD-33, AD-38): the Board is retired, so a Portfolio Entry can hold no fact read from it.
-- A Crew member's facts become the commits they authored in the Handovers of the Job's accepted Milestones, read
-- from delivery's records and matched to the person by the GitHub numeric ID their profile carries (AD-10); the
-- Mentor's stay the Milestones they handed over. No entry names a Work Item or a review (FR-50, AD-33).
-- V202610211600 is superseded in these three columns; it stays as Flyway history.
--
-- What this loses: work_items_approved, a Crew member's Work Items that reached "Xong" through the Mentor's
-- approval, and reviews_written, how many reviews (approvals and returns) the Mentor wrote on the Board. Both were
-- counted from work.work_item_history, which this story drops, so neither can be recomputed and neither can be
-- kept. Entries frozen before this story keep their milestones_accepted, their Job Types, their Skills and their
-- summary.
--
-- commits_authored is NULL wherever vibefix did not count, and NULL keeps that meaning everywhere: the entry's
-- screens draw it as nothing rather than as zero, because a published zero would be vibefix asserting that a
-- person contributed nothing. Three cases give NULL: an entry frozen before this story; an Assessment Job, which
-- hands over no code and so records no commit at all; and a Job whose contributing Handover hit GitHub's
-- 250-commit compare cut (delivery.handover.commits_truncated), where vibefix knows a floor and not a count.
--
-- The two unnamed CHECK constraints that tied the old columns to the role go with them (PostgreSQL drops a table
-- constraint that involves a dropped column), and the pair written here is deliberately not the same rule. The old
-- pair tied presence to the role in both directions. A CHECK is evaluated on the new tuple of every INSERT *and
-- UPDATE* -- NOT VALID skips only the initial scan of existing rows, never later writes -- so under that rule a row
-- whose count is NULL could never be published again (Portfolios.publish sets published_at) nor cleared by a
-- Product deletion (ReputationPersonalData.deleteProduct clears product_name and job_title), and the Crew member's
-- data-deletion confirmation that triggers the publish would roll back with it.
--
-- What the column must guarantee is exclusivity: no entry ever carries the other role's fact. Whether the fact of
-- its own role is present is the application's business, because only the application knows whether vibefix
-- counted it -- Portfolios.create writes both columns, one of them NULL by role, and the Crew member's NULL in the
-- three cases above.
-- Personal data: one column added, two deleted, all three facts frozen from records and never typed by anyone; the
-- entry stays exported for its person and deleted on their erasure, with one fact fewer.

ALTER TABLE reputation.portfolio_entry
    DROP COLUMN work_items_approved,
    DROP COLUMN reviews_written,
    ADD COLUMN commits_authored INTEGER CHECK (commits_authored >= 0);

ALTER TABLE reputation.portfolio_entry
    ADD CONSTRAINT portfolio_entry_crew_facts CHECK (role = 'CREW' OR commits_authored IS NULL),
    ADD CONSTRAINT portfolio_entry_mentor_facts CHECK (role = 'MENTOR' OR milestones_handed_over IS NULL);
