-- Where notifications are emailed (FR-48, AD-27): the account's GitHub primary verified email,
-- read at every sign-in through the user:email scope. Personal data: exported, cleared on erasure.
ALTER TABLE identity.account ADD COLUMN github_email TEXT;
