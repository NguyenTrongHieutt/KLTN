-- Board moves (FR-24, AD-39): a move between "Cần làm" and "Đang làm" writes a MOVED history entry with
-- the status it left and entered.

ALTER TABLE work.work_item_history DROP CONSTRAINT work_item_history_action_check;

ALTER TABLE work.work_item_history
    ADD CONSTRAINT work_item_history_action_check CHECK (action IN ('SEEDED', 'CREATED', 'UPDATED', 'MOVED'));
