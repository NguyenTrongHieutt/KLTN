-- Story 23.9b (FR-37, AD-9, AD-10, R1, R16): the Merge App itself is retired. It was the third GitHub App of each
-- environment (AD-10): each Customer installed it on their own Source Repository for one Final Merge, and vibefix
-- read the installation, pushed the merge commit and opened and merged the pull request with its tokens. Story 23.9a
-- moved the Final Merge onto the Customer's own GitHub Actions, so nothing calls it any more; with this story
-- MergeApp, GitHubMergeApp and App.MERGE are gone from the code, and the App leaves every environment's
-- configuration.
--
-- Story 23.9a left these two for here: the table of installations, and the authorization's bound_at, which recorded
-- when an installation passed AD-10's checks. There is no BOUND state and no installation any more, so neither has a
-- reader. Everything else of repo.final_merge_authorization stays: the row is still the Final Merge's identity, the
-- FR-37 deadline's anchor and repo.zip_fallback's reference until Story 23.10. The dropped table's GRANT goes with
-- it. IF EXISTS, so a database that never had them migrates all the same.
-- Personal data: repo.merge_installation held a Customer's GitHub account ID and login, which go with the table; no
-- PersonalDataParticipant covered it, and none loses a table (RepoPersonalData).

ALTER TABLE repo.final_merge_authorization
    DROP COLUMN IF EXISTS bound_at;

DROP TABLE IF EXISTS repo.merge_installation;
