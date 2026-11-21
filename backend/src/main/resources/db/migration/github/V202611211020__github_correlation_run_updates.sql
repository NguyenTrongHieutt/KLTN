-- Story 24.5a (AD-44, AD-46): a hint about a correlation's run (its completed workflow_run, its path report, or the
-- exchange's refusal) wakes the operation that dispatched it. A hint can arrive while that operation is still in its
-- dispatch, before its own row names the correlation, so the wake finds no one to wake. The time of the last hint is
-- kept on the correlation, and the operation checks it right after it records the dispatch. Existing rows start
-- with none.
-- Personal data: none; a time, when something about a run was heard.

ALTER TABLE github.exchange_correlation ADD COLUMN run_updated_at TIMESTAMPTZ;
