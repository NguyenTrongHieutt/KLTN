-- FR-8, AD-18: resolving a Finding. Its resolvers are the accounts that may close it: the Product's
-- Customer for an Intake Finding, written when it opens; later epics set others (setResolvers). A
-- resolved Finding records how, by whom, and when; the confirmation is also in the Audit Log.
-- Personal data: resolvers and resolved_by_account_id name accounts (ScanningPersonalData).

ALTER TABLE scanning.finding DROP CONSTRAINT finding_status_check;

ALTER TABLE scanning.finding
    ADD CONSTRAINT finding_status_check CHECK (status IN ('OPEN', 'RESOLVED'));

ALTER TABLE scanning.finding
    ADD COLUMN resolution             TEXT CHECK (resolution IN ('ROTATED')),
    ADD COLUMN resolved_by_account_id UUID,
    ADD COLUMN resolved_at            TIMESTAMPTZ;

ALTER TABLE scanning.finding
    ADD CONSTRAINT finding_resolved_check CHECK ((status = 'OPEN') = (resolution IS NULL AND resolved_at IS NULL));

CREATE TABLE scanning.finding_resolver
(
    finding_id UUID NOT NULL REFERENCES scanning.finding (id) ON DELETE CASCADE,
    account_id UUID NOT NULL,
    PRIMARY KEY (finding_id, account_id)
);

CREATE INDEX finding_resolver_account_idx ON scanning.finding_resolver (account_id);

GRANT SELECT, INSERT, UPDATE, DELETE ON scanning.finding_resolver TO ${runtime_role};
