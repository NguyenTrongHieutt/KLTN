-- Schema github (AD-44, AD-45, AD-46, Story 23.6a): a run of the published vibefix.yml is dispatched in the
-- Customer's Source Repository through their own installation of the Source App, so its correlation records that
-- installation: its run is found, read back and polled with a token from it, narrowed to the Source Repository.
-- The token the exchange issues is still minted by the Platform App over the grant's platform repositories.
-- Each correlation also keeps its run's page on GitHub, recorded when the run is found, and the conclusion of the
-- run's first attempt with what that alone does not say (ACTIONS_QUOTA: a failure none of whose jobs started),
-- recorded when a read finds that attempt completed; a Customer's re-run changes neither.
-- Personal data: a SOURCE row names the Customer's Source Repository, whose owner may be their GitHub login, and
-- links its run; no row holds an account ID. The revocation poller deletes every correlation 30 days after its
-- expiry [ASSUMPTION], unless its token is still out.

ALTER TABLE github.exchange_correlation
    ADD COLUMN source_installation_id BIGINT CHECK (source_installation_id > 0),
    ADD COLUMN run_html_url           TEXT CHECK (length(run_html_url) BETWEEN 1 AND 2048),
    ADD COLUMN run_conclusion         TEXT CHECK (run_conclusion ~ '^[a-z_]{1,40}$'),
    ADD COLUMN run_marker             TEXT CHECK (run_marker IN ('ACTIONS_QUOTA')),
    ADD CONSTRAINT exchange_correlation_source_installation_check
        CHECK ((dispatching_app = 'SOURCE') = (source_installation_id IS NOT NULL)),
    ADD CONSTRAINT exchange_correlation_run_read_check
        CHECK ((run_html_url IS NULL AND run_conclusion IS NULL) OR run_id IS NOT NULL),
    ADD CONSTRAINT exchange_correlation_marker_conclusion_check
        CHECK (run_marker IS NULL OR run_conclusion IS NOT NULL);

-- The revocation poller's purge.
GRANT DELETE ON github.exchange_correlation TO ${runtime_role};
