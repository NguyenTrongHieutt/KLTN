-- Schema github (AD-46, D1, R30, Story 23.8b): the runner's GitHub Actions minutes, one row per month in Vietnam
-- time. A daily job lists the runner's runs created since the month began and sums each run's billed duration,
-- rounded up to the minute [ASSUMPTION; Story 23.12 confirms what GitHub reports], against
-- vibefix.github.runner-minutes-budget (2,000 on GitHub Free). The row keeps the last sum, so the alert
-- github.runner_minutes_80 is raised once a month: alerted_at marks the month, exactly as
-- notification.email_budget_day marks the day for the email budget.
-- Personal data: none; a month, two counts and two times.

CREATE TABLE github.runner_minutes_month
(
    -- The month's first day in Vietnam time, which is how GitHub's own monthly allowance is read here.
    month        DATE PRIMARY KEY,
    used_minutes INTEGER     NOT NULL CHECK (used_minutes >= 0),
    budget       INTEGER     NOT NULL CHECK (budget > 0),
    runs         INTEGER     NOT NULL CHECK (runs >= 0),
    read_at      TIMESTAMPTZ NOT NULL,
    alerted_at   TIMESTAMPTZ
);

GRANT SELECT, INSERT, UPDATE, DELETE ON github.runner_minutes_month TO ${runtime_role};
