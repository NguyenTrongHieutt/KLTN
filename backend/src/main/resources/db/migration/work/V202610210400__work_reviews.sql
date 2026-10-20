-- The Review Gate (FR-25, AD-39): a submission, an approval, or a return is a history entry with the
-- person, the note or comment, the Self-check answers, and the time, written with the status change.
-- "Bị trả lại" is derived from the latest of these entries.

ALTER TABLE work.work_item_history
    ADD COLUMN comment    TEXT CHECK (char_length(comment) <= 4000),
    ADD COLUMN self_check JSONB;

ALTER TABLE work.work_item_history DROP CONSTRAINT work_item_history_action_check;

ALTER TABLE work.work_item_history
    ADD CONSTRAINT work_item_history_action_check
        CHECK (action IN ('SEEDED', 'CREATED', 'UPDATED', 'MOVED', 'SUBMITTED', 'APPROVED', 'RETURNED'));
