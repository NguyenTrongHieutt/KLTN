-- Story 23.2 (D9, R16, R25, FR-33): vibefix no longer tracks the heads of Working Repositories. A push after a
-- handover voids nothing; only the acceptance merge compares, through GitHub's sha on handover/{id}, and its 409
-- applies M3. The recorded heads and the push-scan rows (no longer written since Story 23.1) go. Neither table has
-- a foreign key, and nothing else refers to them.

DROP TABLE repo.branch_head;

DROP TABLE repo.push_scan;
