-- Story 21.7 (FR-60, FR-69, 21.4's deferred entry): a Mentor's recruitment note is reportable. target_id is the Job, and
-- author_account_id the Mentor. FR-60 hides no such note, so the Administrator acts on the person, and since the Mentor
-- can rewrite it afterwards the report keeps it as it read at filing, in reported_text. Personal data: the note is the
-- Mentor's text, public while it showed; it is cleared on the Mentor's erasure and deleted with the report.

ALTER TABLE admin.abuse_report DROP CONSTRAINT abuse_report_kind_check;

ALTER TABLE admin.abuse_report
    ADD CONSTRAINT abuse_report_kind_check CHECK (kind IN ('PRODUCT', 'JOB_POST', 'PAGE', 'RATING', 'JOB_CHAT_MESSAGE',
                                                           'CREW_CHAT_MESSAGE', 'WORK_ITEM_NOTE', 'USER',
                                                           'RECRUITMENT_NOTE'));

ALTER TABLE admin.abuse_report
    ADD COLUMN reported_text TEXT,
    ADD CONSTRAINT abuse_report_reported_text_check CHECK (reported_text IS NULL OR kind = 'RECRUITMENT_NOTE');

GRANT UPDATE (reported_text) ON admin.abuse_report TO ${runtime_role};
