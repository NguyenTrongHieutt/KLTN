-- Story 23.6b (R4, AD-11, AD-24, AD-44, AD-46, FR-8, FR-18): J7's Setup waits for the Customer before its busy marker.
-- From J6 it checks the Source App and vibefix.yml, reads the default branch's head, and waits as CONFIRMING (live,
-- with no marker) until the Customer self-confirms that head (N-32). Each confirmation is a repo.source_confirmation
-- row: the Setup, the account, the head SHA, the notice version and the time, audited and never changed but for an
-- erasure, which anonymizes it. A confirmation at the current head makes the Setup RUNNING under the marker: it creates
-- and reads back the private repositories, records the exchange correlation of the copy dispatched on the Customer's
-- GitHub Actions, polls that run, and reads both repositories back at the confirmed SHA. A head that moved after the
-- confirmation (the exchange's refusal "sha") sends it back to CONFIRMING with the new head; the earlier confirmation
-- stays. The new blocks are EXCHANGE_REFUSED (with the exchange's reason), RUN_FAILED and ACTIONS_QUOTA. A Setup still
-- waiting when the Job leaves Starting is ENDED: nothing failed, and nothing more will happen to it.
-- Columns: head_sha and head_read_at, the head the Customer is asked to confirm; approved_sha (the copied commit, as
-- before), confirmation_id and confirmed_at, the confirmation the copy runs for; intermediary_at and working_at, each
-- repository created and read back; correlation_id and dispatched_at, the current run's correlation (internal IDs
-- only), with run_status and run_conclusion as last read; exchange_reason, the exchange's refusal; copied_at, both
-- repositories read back at approved_sha.
-- Old rows: a Setup still RUNNING waits for the Customer from its first step (its pending operation checks again and
-- releases its old marker); a DONE one counts as confirmed at its check, with no confirmation record; the others
-- forget the commit and the copy steps they had, so a retry asks the Customer first. The CHECKs that name a step or a
-- status go by their definition, whatever their names, and come back for the new chain.
-- Personal data: repo.source_confirmation names the confirming account; exported for them, anonymized on their erasure,
-- and deleted with the Product (RepoPersonalData).

DO $$
DECLARE
    found RECORD;
BEGIN
    FOR found IN SELECT conname FROM pg_constraint
                 WHERE conrelid = 'repo.job_setup'::regclass AND contype = 'c'
                   AND pg_get_constraintdef(oid) ~ '(status|blocked_reason|checked_at|intermediary_at|working_at|finished_at)'
    LOOP
        EXECUTE format('ALTER TABLE repo.job_setup DROP CONSTRAINT %I', found.conname);
    END LOOP;
END $$;

DROP INDEX repo.job_setup_live_idx;

ALTER TABLE repo.job_setup
    ADD COLUMN head_sha        TEXT CHECK (head_sha ~ '^[0-9a-f]{40}$'),
    ADD COLUMN head_read_at    TIMESTAMPTZ,
    ADD COLUMN confirmation_id UUID,
    ADD COLUMN confirmed_at    TIMESTAMPTZ,
    ADD COLUMN correlation_id  UUID,
    ADD COLUMN dispatched_at   TIMESTAMPTZ,
    ADD COLUMN run_status      TEXT CHECK (run_status IN ('QUEUED', 'IN_PROGRESS', 'COMPLETED')),
    ADD COLUMN run_conclusion  TEXT CHECK (run_conclusion ~ '^[a-z_]{1,40}$'),
    ADD COLUMN exchange_reason TEXT CHECK (exchange_reason ~ '^[a-z_]{1,20}$'),
    ADD COLUMN copied_at       TIMESTAMPTZ;

UPDATE repo.job_setup SET head_sha = approved_sha, head_read_at = checked_at WHERE checked_at IS NOT NULL;

UPDATE repo.job_setup SET confirmed_at = checked_at, copied_at = working_at WHERE status = 'DONE';

UPDATE repo.job_setup SET approved_sha = NULL, intermediary_at = NULL, working_at = NULL WHERE status <> 'DONE';

UPDATE repo.job_setup
SET status = 'CONFIRMING', checked_at = NULL, head_sha = NULL, head_read_at = NULL
WHERE status = 'RUNNING';

ALTER TABLE repo.job_setup
    ADD CONSTRAINT job_setup_status_check CHECK (status IN ('CONFIRMING', 'RUNNING', 'BLOCKED', 'FAILED', 'DONE', 'ENDED')),
    ADD CONSTRAINT job_setup_blocked_reason_check CHECK (blocked_reason IN ('SOURCE_APP_MISSING',
        'SOURCE_APP_WRONG_REPOSITORY', 'WORKFLOW_MISSING', 'WORKFLOW_MISMATCH', 'NO_ADMIN_PERMISSION', 'EXCHANGE_REFUSED',
        'RUN_FAILED', 'ACTIONS_QUOTA')),
    ADD CONSTRAINT job_setup_blocked_check CHECK ((status = 'BLOCKED') = (blocked_reason IS NOT NULL)),
    ADD CONSTRAINT job_setup_finished_check CHECK ((status IN ('CONFIRMING', 'RUNNING')) = (finished_at IS NULL)),
    ADD CONSTRAINT job_setup_checked_check CHECK (checked_at IS NULL
        OR (head_sha IS NOT NULL AND source_full_name IS NOT NULL AND default_branch IS NOT NULL)),
    ADD CONSTRAINT job_setup_head_check CHECK ((head_sha IS NULL) = (head_read_at IS NULL)),
    ADD CONSTRAINT job_setup_confirmed_check CHECK ((confirmed_at IS NULL) = (approved_sha IS NULL)
        AND (confirmed_at IS NULL OR checked_at IS NOT NULL) AND (confirmation_id IS NULL OR confirmed_at IS NOT NULL)),
    ADD CONSTRAINT job_setup_confirming_check CHECK (status <> 'CONFIRMING' OR confirmed_at IS NULL),
    ADD CONSTRAINT job_setup_running_check CHECK (status <> 'RUNNING' OR confirmation_id IS NOT NULL),
    ADD CONSTRAINT job_setup_intermediary_check CHECK (intermediary_at IS NULL
        OR (confirmed_at IS NOT NULL AND intermediary_github_id IS NOT NULL)),
    ADD CONSTRAINT job_setup_working_check CHECK (working_at IS NULL
        OR (intermediary_at IS NOT NULL AND working_github_id IS NOT NULL)),
    ADD CONSTRAINT job_setup_run_check CHECK ((correlation_id IS NULL) = (dispatched_at IS NULL)
        AND (correlation_id IS NULL OR working_at IS NOT NULL)
        AND ((run_status IS NULL AND run_conclusion IS NULL) OR correlation_id IS NOT NULL)),
    ADD CONSTRAINT job_setup_copied_check CHECK (copied_at IS NULL OR working_at IS NOT NULL),
    ADD CONSTRAINT job_setup_done_check CHECK (status <> 'DONE' OR copied_at IS NOT NULL),
    ADD CONSTRAINT job_setup_exchange_check CHECK ((blocked_reason IS DISTINCT FROM 'EXCHANGE_REFUSED'
        OR exchange_reason IS NOT NULL) AND (exchange_reason IS NULL OR blocked_reason IS NOT DISTINCT FROM 'EXCHANGE_REFUSED'
        OR (status = 'CONFIRMING' AND exchange_reason = 'sha')));

CREATE UNIQUE INDEX job_setup_live_idx ON repo.job_setup (job_id) WHERE status IN ('CONFIRMING', 'RUNNING', 'DONE');

CREATE INDEX job_setup_confirmation_idx ON repo.job_setup (confirmation_id) WHERE confirmation_id IS NOT NULL;

CREATE TABLE repo.source_confirmation
(
    id             UUID PRIMARY KEY,
    job_id         UUID        NOT NULL,
    product_id     UUID        NOT NULL,
    setup_id       UUID        NOT NULL REFERENCES repo.job_setup (id),
    account_id     UUID,
    head_sha       TEXT        NOT NULL CHECK (head_sha ~ '^[0-9a-f]{40}$'),
    notice_version TEXT        NOT NULL CHECK (notice_version ~ '^[0-9]{1,3}\.[0-9]{1,3}$'),
    confirmed_at   TIMESTAMPTZ NOT NULL
);

CREATE INDEX source_confirmation_setup_idx ON repo.source_confirmation (setup_id, confirmed_at);

CREATE INDEX source_confirmation_account_idx ON repo.source_confirmation (account_id);

CREATE INDEX source_confirmation_product_idx ON repo.source_confirmation (product_id);

GRANT SELECT, INSERT, DELETE ON repo.source_confirmation TO ${runtime_role};

-- A confirmation never changes, but for an erasure, which clears who made it.
GRANT UPDATE (account_id) ON repo.source_confirmation TO ${runtime_role};
