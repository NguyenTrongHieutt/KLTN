-- Story 21.6 (FR-25, R8, X7, AD-39): the Mentor's own Work Item moves In progress -> Done with the Self-check answers
-- and no review, recorded as UNREVIEWED ("Xong · không cần duyệt"), which work.criteriaDone counts.

ALTER TABLE work.work_item_history DROP CONSTRAINT work_item_history_action_check;

ALTER TABLE work.work_item_history
    ADD CONSTRAINT work_item_history_action_check
        CHECK (action IN ('SEEDED', 'CREATED', 'UPDATED', 'MOVED', 'SUBMITTED', 'APPROVED', 'RETURNED', 'UNREVIEWED', 'NOTE',
                          'ATTACHED', 'DETACHED'));
