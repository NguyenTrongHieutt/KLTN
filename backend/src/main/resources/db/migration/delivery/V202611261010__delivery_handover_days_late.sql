-- FR-87, AD-48 (Story 25.1): every handover keeps how many days after the Milestone's due date in force it came
-- (0 when on time). job computes the figure at M2 and delivery stores it with the handover, never changing it, so the
-- Delivery Report and the Evidence Package can show "Bàn giao trễ {n} ngày". No money or state reads it.
-- Handovers recorded before this migration keep NULL: the due date in force at their moment is job's to know and was
-- not recorded, and the report shows nothing for them.
-- Personal data: none.

ALTER TABLE delivery.handover
    ADD COLUMN days_late INTEGER CHECK (days_late IS NULL OR days_late >= 0);
