-- Story 8.8 (FR-37, FR-38, AD-11, SG-9, SG-15): the Final Merge of a bound authorization, and the
-- Intermediary heads repo read back after each handover merge it made, which J9's guard compares with.
-- A final merge is CHECKING (the preconditions, the Sensitive Changes, and the trial merge), READY for
-- the Customer's confirmation, CONFIRMED while the merge runs under the FINAL_MERGE marker, MERGED once
-- read back; CONFLICT and REFUSED route to the ZIP Fallback (Story 8.9).
-- Personal data: who confirmed; erasure clears it (RepoPersonalData).

CREATE TABLE repo.handover_merge
(
    job_id       UUID        NOT NULL,
    pull_number  INTEGER     NOT NULL,
    handover_sha TEXT        NOT NULL,
    head_after   TEXT        NOT NULL,
    merged_at    TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (job_id, pull_number)
);

CREATE TABLE repo.final_merge
(
    authorization_id UUID PRIMARY KEY REFERENCES repo.final_merge_authorization (id),
    job_id           UUID        NOT NULL,
    product_id       UUID        NOT NULL,
    status           TEXT        NOT NULL CHECK (status IN ('CHECKING', 'READY', 'CONFIRMED', 'MERGED', 'CONFLICT',
                                                            'REFUSED')),
    intermediary_sha TEXT,
    source_branch    TEXT,
    sensitive        JSONB       NOT NULL DEFAULT '[]',
    workflows        JSONB       NOT NULL DEFAULT '[]',
    refusal          TEXT,
    checked_at       TIMESTAMPTZ,
    confirmed_at     TIMESTAMPTZ,
    confirmed_by     UUID,
    operation_key    TEXT,
    branch           TEXT,
    pull_number      INTEGER,
    pull_url         TEXT,
    merged_at        TIMESTAMPTZ,
    created_at       TIMESTAMPTZ NOT NULL,
    CHECK ((status IN ('CONFLICT', 'REFUSED')) = (refusal IS NOT NULL)),
    CHECK ((status = 'MERGED') = (merged_at IS NOT NULL))
);

CREATE INDEX final_merge_job ON repo.final_merge (job_id);
CREATE INDEX final_merge_confirmed_by ON repo.final_merge (confirmed_by) WHERE confirmed_by IS NOT NULL;

GRANT SELECT, INSERT ON repo.handover_merge TO ${runtime_role};
GRANT DELETE ON repo.handover_merge TO ${runtime_role};
GRANT SELECT, INSERT, UPDATE, DELETE ON repo.final_merge TO ${runtime_role};
