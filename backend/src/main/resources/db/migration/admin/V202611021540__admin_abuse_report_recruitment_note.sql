-- Story 21.7 (FR-60, FR-69, 21.4's deferred entry): a Mentor's recruitment note is reportable. target_id is the Job, and
-- author_account_id the Mentor. FR-60 hides no such note, so the Administrator acts on the person. Personal data: as the
-- other kinds; nothing new is kept.

ALTER TABLE admin.abuse_report DROP CONSTRAINT abuse_report_kind_check;

ALTER TABLE admin.abuse_report
    ADD CONSTRAINT abuse_report_kind_check CHECK (kind IN ('PRODUCT', 'JOB_POST', 'PAGE', 'RATING', 'JOB_CHAT_MESSAGE',
                                                           'CREW_CHAT_MESSAGE', 'WORK_ITEM_NOTE', 'USER',
                                                           'RECRUITMENT_NOTE'));
