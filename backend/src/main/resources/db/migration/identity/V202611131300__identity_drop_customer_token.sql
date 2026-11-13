-- Story 23.5d (R31, AD-10, AD-22, SG-9): the GitHub linking token keeps identity only. It is used once in the
-- linking callback, like Google's, never stored, and its whole OAuth grant is revoked at once, so no Customer token
-- is kept for repository reads any more (the Source App reads repositories since Story 23.5). The stored tokens go
-- with their table. They are not revoked on GitHub: the dev OAuth App's authorizations granted before this story
-- stay listed on the author's GitHub account until revoked there (deploy/README.md).
-- Personal data: the sealed tokens are deleted; nothing new is kept.

DROP TABLE identity.customer_token;
