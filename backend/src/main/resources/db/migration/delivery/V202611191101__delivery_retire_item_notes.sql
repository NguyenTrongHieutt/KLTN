-- Story 24.2b (D1, FR-23, FR-32, AD-23, AD-33, AD-39 removed): a Milestone Handover no longer reads the Board, so
-- it carries neither a note on an unfinished Work Item nor a Work Item link on its commits. V202610210900 (the
-- table) and V202610221910 (the column) are superseded in these two places; both stay as Flyway history.
--
-- What this loses: delivery.handover_item_note held, per handover, one Work Item that was not in "Xong" through the
-- Mentor's approval, with its key, title, status and the Mentor's FR-47-masked explanation. The Customer never read
-- it (AD-39); the Mentor and the Crew did. What takes its place already exists and already runs first: every
-- Acceptance Criterion of the Milestone needs "Dat" or "Chua dat" and a note, refused
-- DeliveryReason.CRITERION_MARK_MISSING, so an unmet criterion is declared as "Chua dat" with the Mentor's own
-- words, which the Customer reads in the Delivery Report (FR-32, AD-23, R42). The refusal WORK_ITEMS_NOT_DONE goes
-- with the gate it guarded, and no new refusal constant arrives.
--
-- delivery.handover_commit.work_item_keys held the {JOBCODE}-{n} keys a commit's message mentioned, which
-- work.work_item_commit resolved against the Job's Board (FR-23). With no Board there is nothing to resolve a key
-- against and nothing to write the column, so it goes rather than stand empty on every new row; its handovers keep
-- every commit they recorded, with its SHA, its message and its author's GitHub ID.
--
-- What stays: delivery.handover, handover_criterion, handover_check, handover_commit itself, the Sensitive Changes,
-- the screenshots, the submissions and the follow-ups, all untouched. The audit entries of the handovers stay (AD-8).
-- Personal data: rows and one column deleted. The Mentor's export loses its itemNotes block in the same change
-- (DeliveryPersonalData), and handover_item_note leaves its table list, so nothing names a table that is gone;
-- nothing new is kept.

DROP TABLE delivery.handover_item_note;

ALTER TABLE delivery.handover_commit
    DROP COLUMN work_item_keys;
