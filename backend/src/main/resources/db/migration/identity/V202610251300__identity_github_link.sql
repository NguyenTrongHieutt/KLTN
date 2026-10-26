-- Story 19.2 (FR-80, AD-10, AD-19, AD-42): GitHub is linked to a signed-in account through the linking
-- OAuth App, never signed in with. github_linked_at is when the link was last made or renewed, shown on
-- ACC-2 as "Xác minh qua GitHub · {tháng/năm}"; the rows from before this change were linked when they
-- were created. Before leaving for GitHub the person accepts N-24, recorded as the LINK_GITHUB
-- acknowledgment with its version. Personal data: github_linked_at is exported, and cleared on erasure
-- and on unlinking.

ALTER TABLE identity.account
    ADD COLUMN github_linked_at TIMESTAMPTZ;

UPDATE identity.account SET github_linked_at = created_at WHERE github_user_id IS NOT NULL;

ALTER TABLE identity.acknowledgment
    DROP CONSTRAINT acknowledgment_notice_check,
    ADD CONSTRAINT acknowledgment_notice_check CHECK (notice IN ('PRIVACY', 'COMMIT_EMAIL', 'LINK_GITHUB'));
