-- FR-15: the selected Mentor's NDA signature (the text version and the time) is matching's evidence,
-- which J4 carries and the Crew rows will copy (AD-30). Held Applications close at J4 (NOT_SELECTED);
-- at J5 the selected one closes as DECLINED by its Mentor or NOT_SIGNED in time.
-- Personal data: exported for the signer, deleted with the account and the Product (MatchingPersonalData).

CREATE TABLE matching.nda_signature
(
    job_id      UUID        NOT NULL,
    account_id  UUID        NOT NULL,
    nda_version TEXT        NOT NULL CHECK (char_length(nda_version) BETWEEN 1 AND 20),
    signed_at   TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (job_id, account_id)
);

CREATE INDEX nda_signature_account_idx ON matching.nda_signature (account_id);

GRANT SELECT, INSERT, DELETE ON matching.nda_signature TO ${runtime_role};

ALTER TABLE matching.application
    DROP CONSTRAINT application_end_reason_check,
    ADD CONSTRAINT application_end_reason_check CHECK (end_reason IN ('WITHDRAWN', 'MENTOR_UNAVAILABLE',
        'JOB_WITHDRAWN', 'JOB_LAPSED', 'NOT_SELECTED', 'DECLINED', 'NOT_SIGNED'));
