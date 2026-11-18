-- Story 24.1 (D2, FR-10, FR-11, FR-32, SG-25): the Documentation Space is retired, so nothing in delivery points at a
-- Page any more. Three things change, and V202610210900, V202610211700 and V202610221910 are superseded by them.
--
-- 1. A follow-up Job named its recommendation by the number it carried in the accepted Report's Markdown. With no
-- Report there is no number, so the Mentor writes the recommendation the draft follows in their own words. Every row
-- written before this story keeps its citation, backfilled as "Đề xuất số {n}" from the number it held, so the
-- Customer still reads which recommendation each draft came from. report_page_id and report_version_no go with it:
-- the accepted handover is found by its status alone from now on.
-- 2. delivery.handover_page listed the Pages updated during a Code Job's Milestone, beside its commits. There are no
-- Pages to list, so the table goes; a Delivery Report recorded before this story loses that one list, and keeps its
-- criteria, its notes, its commits and its Sensitive Changes.
-- 3. delivery.handover pinned an Assessment Job's Report Page at the version it handed over. Dropping the two columns
-- drops the CHECK that tied them to each other, which names no other column. Until Story 24.3 adds the handover's own
-- document link, an Assessment Job's Delivery Report carries no report at all -- only its Acceptance Criteria as the
-- Mentor marked them, which is the Assessment deliverable from now on.
-- Personal data: nothing new is kept. recommendation_text holds the Mentor's own sentence, which their erasure leaves
-- as it leaves the rest of the draft they wrote; the row is deleted with its Product (DeliveryPersonalData, which
-- already covers delivery.follow_up and delivery.handover).

ALTER TABLE delivery.follow_up
    ADD COLUMN recommendation_text TEXT;

UPDATE delivery.follow_up
SET recommendation_text = 'Đề xuất số ' || recommendation_no;

ALTER TABLE delivery.follow_up
    ALTER COLUMN recommendation_text SET NOT NULL,
    ADD CONSTRAINT follow_up_recommendation_text_check
        CHECK (char_length(recommendation_text) BETWEEN 1 AND 500),
    DROP COLUMN report_page_id,
    DROP COLUMN report_version_no,
    DROP COLUMN recommendation_no;

-- follow_up_job_idx was dropped with recommendation_no, which it ordered by; the follow-ups of a Job are read in
-- their writing order now.
CREATE INDEX follow_up_job_idx ON delivery.follow_up (job_id, created_at);

GRANT UPDATE (recommendation_text) ON delivery.follow_up TO ${runtime_role};

DROP TABLE delivery.handover_page;

ALTER TABLE delivery.handover
    DROP COLUMN report_page_id,
    DROP COLUMN report_version_no;
