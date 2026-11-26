-- Story 25.2a part 4 (AD-47 exception 8, B-17): nobody rates themselves. In a Self-mentored Job the Rating Window names
-- the Customer as its Mentor too, so "the Customer rates the Mentor" would be a Rating of oneself. Ratings.rate refuses
-- it first with VALIDATION RATEE_NOT_ALLOWED; this CHECK is the backstop. A Rating whose rater was erased
-- (rater_account_id NULL) passes, as before.
-- Old rows: in a normal Job the Customer, the Mentor and the Crew are different accounts (AD-43), so no row has the
-- same rater and ratee.
-- Personal data: unchanged.

ALTER TABLE reputation.rating
    ADD CONSTRAINT rating_not_of_oneself_check CHECK (rater_account_id <> ratee_account_id);
