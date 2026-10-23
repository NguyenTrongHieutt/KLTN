-- Schema admin (FR-69, AD-20, AD-34, Story 16.1): the Abuse Reports users file on content or a user they can
-- see, at most 10 a day each, and how an Administrator decided them. The label is a title or a name, never a
-- message's or a note's text. A report on a chat message or a Work Item note keeps the read grant its
-- assignee holds on that one item.
-- Personal data: the reporter's own reports are exported; on erasure the reporter, the reported author, and
-- the Administrators are cleared (a reported user's name too); a Product's deletion deletes its reports.

CREATE SCHEMA admin;

GRANT USAGE ON SCHEMA admin TO ${runtime_role};

CREATE TABLE admin.abuse_report
(
    id                  UUID PRIMARY KEY,
    reporter_account_id UUID,
    kind                TEXT        NOT NULL CHECK (kind IN ('PRODUCT', 'JOB_POST', 'PAGE', 'RATING', 'JOB_CHAT_MESSAGE',
                                                              'CREW_CHAT_MESSAGE', 'WORK_ITEM_NOTE', 'USER')),
    target_id           UUID        NOT NULL,
    job_id              UUID,
    product_id          UUID,
    author_account_id   UUID,
    label               TEXT        NOT NULL CHECK (char_length(label) BETWEEN 1 AND 300),
    reason              TEXT        NOT NULL CHECK (char_length(reason) BETWEEN 1 AND 500),
    status              TEXT        NOT NULL CHECK (status IN ('OPEN', 'HIDDEN', 'ACTION_TAKEN', 'DISMISSED')),
    created_at          TIMESTAMPTZ NOT NULL,
    assigned_admin_id   UUID,
    assigned_at         TIMESTAMPTZ,
    grant_id            UUID,
    decided_by_admin_id UUID,
    outcome             TEXT CHECK (char_length(outcome) BETWEEN 1 AND 500),
    decided_at          TIMESTAMPTZ,
    CHECK ((status = 'OPEN') = (decided_at IS NULL)),
    CHECK ((decided_at IS NULL) = (outcome IS NULL)),
    CHECK ((assigned_admin_id IS NULL) OR (assigned_at IS NOT NULL))
);

CREATE INDEX abuse_report_reporter_idx ON admin.abuse_report (reporter_account_id, created_at);

CREATE UNIQUE INDEX abuse_report_open_idx ON admin.abuse_report (reporter_account_id, kind, target_id) WHERE status = 'OPEN';

CREATE INDEX abuse_report_queue_idx ON admin.abuse_report (status, created_at);

CREATE INDEX abuse_report_product_idx ON admin.abuse_report (product_id) WHERE product_id IS NOT NULL;

GRANT SELECT, INSERT, DELETE ON admin.abuse_report TO ${runtime_role};

GRANT UPDATE (reporter_account_id, author_account_id, label, status, assigned_admin_id, assigned_at, grant_id,
    decided_by_admin_id, outcome, decided_at) ON admin.abuse_report TO ${runtime_role};
