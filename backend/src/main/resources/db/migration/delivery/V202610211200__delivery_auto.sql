-- FR-35: a handover the Customer leaves undecided is accepted automatically when its Auto-acceptance
-- Deadline is due (delivery.auto_acceptance, with delivery.acceptance_reminder twice before it). Such a
-- handover is ACCEPTED with no decider and the auto flag, which the Customer View, the Audit Log, and
-- statistics label "Đã tự nghiệm thu".
-- Personal data: none beyond the handover's.

ALTER TABLE delivery.handover
    ADD COLUMN auto BOOLEAN NOT NULL DEFAULT false,
    ADD CONSTRAINT handover_auto_check CHECK (NOT auto OR (status = 'ACCEPTED' AND decided_by IS NULL));

GRANT UPDATE (auto) ON delivery.handover TO ${runtime_role};
