-- FR-33, FR-34, UX-DR33: the Customer's decision on a handover. A handover is accepted or sent back
-- for a revision once, with the time and the person; the Customer's own mark and comment on each
-- criterion are kept as they sent them. Nothing else on a handover changes.
-- Personal data: the decider is cleared on erasure, and the checks are exported for them and deleted
-- with the Product (DeliveryPersonalData).

ALTER TABLE delivery.handover DROP CONSTRAINT handover_status_check;

ALTER TABLE delivery.handover
    ADD CONSTRAINT handover_status_check CHECK (status IN ('SUBMITTED', 'ACCEPTED', 'REVISION_REQUESTED')),
    ADD COLUMN decided_at TIMESTAMPTZ,
    ADD COLUMN decided_by UUID,
    ADD CONSTRAINT handover_decided_check CHECK ((status = 'SUBMITTED') = (decided_at IS NULL)),
    ADD CONSTRAINT handover_decider_check CHECK (decided_by IS NULL OR decided_at IS NOT NULL);

CREATE INDEX handover_decider_idx ON delivery.handover (decided_by) WHERE decided_by IS NOT NULL;

GRANT UPDATE (status, decided_at, decided_by) ON delivery.handover TO ${runtime_role};

CREATE TABLE delivery.handover_check
(
    handover_id  UUID    NOT NULL REFERENCES delivery.handover (id),
    criterion_id UUID    NOT NULL,
    met          BOOLEAN NOT NULL,
    comment      TEXT CHECK (char_length(comment) <= 2000),
    PRIMARY KEY (handover_id, criterion_id)
);

GRANT SELECT, INSERT, DELETE ON delivery.handover_check TO ${runtime_role};
