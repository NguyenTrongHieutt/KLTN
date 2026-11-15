-- Story 23.10a (FR-37, FR-38, D10, AD-24): the closing choice has a second path, and the code receipt is its record.
-- "Tôi đã tự tải / clone từ GitHub" writes one row per Job -- who confirmed and when -- in the same transaction that
-- spends the choice, closes its to-do and applies J9 with finish = SELF_DOWNLOAD. The row is the only answer to "did
-- the Customer say they have the code", which Story 23.10b asks at the closing-choice deadline and Story 23.10c asks
-- before the repositories are deleted. The Final Merge is never recorded here: a branch read back is its own evidence
-- (repo.final_merge.status = 'DONE').
--
-- A row of its own, keyed by the Job, answers that question without widening repo.final_merge_authorization, which
-- stays the choice's identity and the FR-37 deadline's anchor, or repo.zip_fallback and job_setup.archive_file_id,
-- which Story 23.10c deletes. The Setup is named so the receipt hangs from the repositories it is about, and a
-- Product deletion takes it with them.
-- A receipt never changes: the runtime role may read, write and delete it, never update it.
-- Personal data: confirmed_by is an account id. An erasure deletes the row with the Customer's other repo rows, and
-- a Product deletion deletes it with its Jobs' (RepoPersonalData); there is nothing to anonymize, because the row
-- says only that this account confirmed at this time.

CREATE TABLE repo.code_receipt
(
    job_id       UUID PRIMARY KEY,
    setup_id     UUID        NOT NULL REFERENCES repo.job_setup (id),
    confirmed_by UUID        NOT NULL,
    confirmed_at TIMESTAMPTZ NOT NULL
);

CREATE INDEX code_receipt_setup_idx ON repo.code_receipt (setup_id);

CREATE INDEX code_receipt_confirmed_by_idx ON repo.code_receipt (confirmed_by);

GRANT SELECT, INSERT, DELETE ON repo.code_receipt TO ${runtime_role};
