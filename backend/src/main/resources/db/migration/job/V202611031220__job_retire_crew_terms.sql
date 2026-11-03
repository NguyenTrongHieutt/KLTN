-- Story 22.4 (FR-42, FR-75, AD-29, AD-30, SG-63): money moves only through the Milestone Splits, so the Crew Terms go:
-- J6 no longer brings a version into force and M1 no longer pins one. The three Crew Terms tables and the M1 pin go
-- together, since none works without the others. Dropping the pin drops milestone_funded_pinned with it; its other
-- half, that a Milestone past Planned has its funding time, comes back on its own.
-- The Administrator read grants no longer name the Crew Terms (AD-20). A stored grant with that item loses it, as
-- AdminReadScope no longer reads it; the Crew Chat stays in every such grant, so none is left empty. Such a grant can
-- no longer tell a retired share Team Issue's from a Rating's, so it ends now: for an open case to be read on, another
-- Administrator takes it over. The audit trail of earlier reads stays (AD-8).
-- Personal data: the Crew Terms' parties go with their tables; nothing new is kept.

DROP TABLE job.crew_terms_confirmation;

DROP TABLE job.crew_terms_share;

DROP TABLE job.crew_terms_version;

ALTER TABLE job.milestone
    DROP COLUMN crew_terms_version_no;

ALTER TABLE job.milestone
    ADD CONSTRAINT milestone_funded_check CHECK (state IN ('PLANNED', 'CANCELLED') OR funded_at IS NOT NULL);

UPDATE job.admin_read_grant
SET items   = (SELECT jsonb_agg(item) FROM jsonb_array_elements(items) AS item WHERE item ->> 'scope' <> 'CREW_TERMS'),
    ends_at = coalesce(ends_at, now())
WHERE items @> '[{"scope": "CREW_TERMS"}]';
