-- Page templates and visibility (FR-30, FR-31, FR-10): a Page may keep the template it started from,
-- and it is team-only (TEAM) unless the Mentor makes it customer-visible (CUSTOMER). An Assessment
-- Report exists at most once per Job and is always customer-visible.

ALTER TABLE work.page
    ADD COLUMN template   TEXT CHECK (template IN ('ASSESSMENT_REPORT', 'SCOPE', 'TECHNICAL_NOTES', 'MEETING_NOTES',
                                                   'HANDOVER', 'USER_GUIDE')),
    ADD COLUMN visibility TEXT NOT NULL DEFAULT 'TEAM' CHECK (visibility IN ('TEAM', 'CUSTOMER')),
    ADD CONSTRAINT page_report_visible CHECK (template IS DISTINCT FROM 'ASSESSMENT_REPORT' OR visibility = 'CUSTOMER');

CREATE UNIQUE INDEX page_report_idx ON work.page (job_id) WHERE template = 'ASSESSMENT_REPORT';
