-- Story 23.9a (FR-37, AD-11, AD-44, AD-46, R1, D7): the Final Merge runs on the Customer's own GitHub Actions. One
-- run of the published vibefix.yml pushes the Intermediary's merge commit to a new vibefix/final-<jobId> branch of
-- the Source Repository with its own GITHUB_TOKEN, vibefix reads that branch back, and the Customer merges it
-- themselves on GitHub. vibefix no longer pushes, opens a pull request, or merges anything in a Customer repository,
-- so the pushed branch's pull request goes and the run takes its place: the correlation, when it was dispatched, the
-- branch, and the run's last status and conclusion. MERGED becomes DONE ("the branch is ready"), and RUNNING is the
-- state between the Customer's N-18 confirmation and the read-back.
--
-- The Merge App is retired with it: an authorization is never BOUND to an installation again, there is no install
-- link state, no organization install request and no refusal. The row itself stays as the Final Merge's identity,
-- the FR-37 deadline's anchor and the closure fallback's (repo.zip_fallback references it); Story 23.10 reshapes
-- that when the self-download path replaces the ZIP. repo.merge_installation and bound_at stay for Story 23.9b,
-- which retires the App itself and the row's other stranded columns; the authorization's foreign key to the
-- installation goes here with its column.
-- Personal data: no new column holds any; who confirmed stays as it was (RepoPersonalData).

-- The Final Merge's own shape.
ALTER TABLE repo.final_merge
    ADD COLUMN correlation_id UUID,
    ADD COLUMN dispatched_at  TIMESTAMPTZ,
    ADD COLUMN run_status     TEXT,
    ADD COLUMN run_conclusion TEXT;

ALTER TABLE repo.final_merge
    DROP COLUMN pull_number,
    DROP COLUMN pull_url;

-- The CHECKs that name MERGED go by their definition, whatever their names. They go before the rows are mapped,
-- because DONE is in neither the old status list nor the old merged_at pairing.
DO $$
DECLARE
    found RECORD;
BEGIN
    FOR found IN SELECT conname FROM pg_constraint
                 WHERE conrelid = 'repo.final_merge'::regclass AND contype = 'c'
                   AND pg_get_constraintdef(oid) LIKE '%MERGED%'
    LOOP
        EXECUTE format('ALTER TABLE repo.final_merge DROP CONSTRAINT %I', found.conname);
    END LOOP;
END $$;

UPDATE repo.final_merge SET status = 'DONE' WHERE status = 'MERGED';

ALTER TABLE repo.final_merge
    ADD CONSTRAINT final_merge_status_check CHECK (status IN ('CHECKING', 'READY', 'CONFIRMED', 'RUNNING', 'DONE',
                                                              'REFUSED', 'CONFLICT')),
    ADD CONSTRAINT final_merge_done_check CHECK ((status = 'DONE') = (merged_at IS NOT NULL));

CREATE INDEX final_merge_correlation ON repo.final_merge (correlation_id) WHERE correlation_id IS NOT NULL;

-- The Merge App's half of the authorization row.
UPDATE repo.final_merge_authorization SET status = 'REQUESTED' WHERE status = 'BOUND';

DO $$
DECLARE
    found RECORD;
BEGIN
    FOR found IN SELECT conname FROM pg_constraint
                 WHERE conrelid = 'repo.final_merge_authorization'::regclass AND contype = 'c'
                   AND (pg_get_constraintdef(oid) LIKE '%BOUND%' OR pg_get_constraintdef(oid) LIKE '%MERGE_APP%')
    LOOP
        EXECUTE format('ALTER TABLE repo.final_merge_authorization DROP CONSTRAINT %I', found.conname);
    END LOOP;
END $$;

-- The two partial indexes name BOUND; the state and installation indexes go with their own columns.
DROP INDEX repo.final_merge_authorization_open_repository;
DROP INDEX repo.final_merge_authorization_open_job;

ALTER TABLE repo.final_merge_authorization
    DROP COLUMN installation_id,
    DROP COLUMN state_hash,
    DROP COLUMN state_expires_at,
    DROP COLUMN request_org,
    DROP COLUMN request_seen_at,
    DROP COLUMN request_refused_at,
    DROP COLUMN refusal,
    DROP COLUMN refused_at;

ALTER TABLE repo.final_merge_authorization
    ADD CONSTRAINT final_merge_authorization_status_check CHECK (status IN ('REQUESTED', 'USED', 'ENDED'));

-- One open Final Merge per Source Repository (AD-13) and per Job.
CREATE UNIQUE INDEX final_merge_authorization_open_repository ON repo.final_merge_authorization (repository_github_id)
    WHERE status = 'REQUESTED';
CREATE UNIQUE INDEX final_merge_authorization_open_job ON repo.final_merge_authorization (job_id)
    WHERE status = 'REQUESTED';
