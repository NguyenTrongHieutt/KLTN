-- Story 21.6 (FR-66, R15): a member who leaves gets one row per External Access item of the confirmed Agreement beside
-- the rows J8 and J10 open, since the platform cannot know what the Customer granted them. crew_row_id tells them apart:
-- the nil UUID on a row of the Job's end, the member's Crew row on a leaver's, which stays when an erasure anonymizes it
-- (member_account_id, member_login and identifiers go, RepoPersonalData). Unlike the account, a Crew row's ID names nobody.

ALTER TABLE repo.revocation_item
    ADD COLUMN crew_row_id       UUID NOT NULL DEFAULT '00000000-0000-0000-0000-000000000000',
    ADD COLUMN member_account_id UUID,
    ADD COLUMN member_login      TEXT;

ALTER TABLE repo.revocation_item
    DROP CONSTRAINT revocation_item_pkey;

ALTER TABLE repo.revocation_item
    ADD PRIMARY KEY (job_id, access_id, crew_row_id);

CREATE INDEX revocation_item_member ON repo.revocation_item (member_account_id) WHERE member_account_id IS NOT NULL;
