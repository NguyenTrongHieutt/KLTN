-- Schema notification: for now, the daily SMTP budget (AD-34). In-app notifications and to-dos
-- arrive with Story 1.7. Owned by the migration role; the runtime role ${runtime_role} gets DML.

CREATE SCHEMA notification;

GRANT USAGE ON SCHEMA notification TO ${runtime_role};

-- Emails sent per UTC day (the provider's day). alerted_at marks the day's
-- notification.email_budget_80 alert, recorded once when the send that crosses the share goes out.
CREATE TABLE notification.email_budget_day
(
    day        DATE PRIMARY KEY,
    sent       INT NOT NULL CHECK (sent >= 0),
    alerted_at TIMESTAMPTZ
);

GRANT SELECT, INSERT, UPDATE ON notification.email_budget_day TO ${runtime_role};
