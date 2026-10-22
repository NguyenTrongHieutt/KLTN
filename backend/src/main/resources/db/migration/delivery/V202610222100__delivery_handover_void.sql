-- Story 8.4 (FR-32, AD-11): a push after a Code Job's handover moved the Working head away from its
-- Handover Commit, so M3 voided it; the handover keeps when. A void is no decision: it has no decider.

ALTER TABLE delivery.handover DROP CONSTRAINT handover_status_check;

ALTER TABLE delivery.handover DROP CONSTRAINT handover_decided_check;

ALTER TABLE delivery.handover
    ADD CONSTRAINT handover_status_check CHECK (status IN ('SUBMITTED', 'ACCEPTED', 'REVISION_REQUESTED', 'VOIDED')),
    ADD CONSTRAINT handover_decided_check CHECK ((status IN ('SUBMITTED', 'VOIDED')) = (decided_at IS NULL)),
    ADD COLUMN voided_at TIMESTAMPTZ,
    ADD CONSTRAINT handover_voided_check CHECK ((status = 'VOIDED') = (voided_at IS NOT NULL));

GRANT UPDATE (voided_at) ON delivery.handover TO ${runtime_role};
