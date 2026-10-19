-- Schema settings: the rule values an Administrator may change (FR-61) and the school email domain
-- allowlist (FR-5). Owned by the migration role; the runtime role ${runtime_role} gets DML table by
-- table. Every value is read live when used (AD-7); durations are copied onto a Job by its own module.

CREATE SCHEMA settings;

GRANT USAGE ON SCHEMA settings TO ${runtime_role};

-- One row per rule, seeded with the PRD defaults (addendum B2); the CHECK holds FR-61's bounds: a
-- value in days between 1 and 30, the active-Job limit between 1 and 10, the Mentor share cap a whole
-- percentage between 10 and 100, and Revision Rounds between 0 and 10.
CREATE TABLE settings.rule_value
(
    rule_key   TEXT PRIMARY KEY,
    value      INTEGER     NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CHECK (CASE rule_key
               WHEN 'REVISION_ROUNDS' THEN value BETWEEN 0 AND 10
               WHEN 'MENTOR_ACTIVE_JOB_LIMIT' THEN value BETWEEN 1 AND 10
               WHEN 'MENTOR_SHARE_CAP_PERCENT' THEN value BETWEEN 10 AND 100
               WHEN 'AUTO_ACCEPTANCE_DAYS' THEN value BETWEEN 1 AND 30
               WHEN 'AUTO_ACCEPTANCE_FIRST_REMINDER_DAY' THEN value BETWEEN 1 AND 30
               WHEN 'AUTO_ACCEPTANCE_SECOND_REMINDER_DAY' THEN value BETWEEN 1 AND 30
               WHEN 'RATING_WINDOW_DAYS' THEN value BETWEEN 1 AND 30
               WHEN 'DISPUTE_RESPONSE_DAYS' THEN value BETWEEN 1 AND 30
               WHEN 'TEAM_ISSUE_ANSWER_DAYS' THEN value BETWEEN 1 AND 30
               WHEN 'TEAM_ISSUE_ESCALATION_DAYS' THEN value BETWEEN 1 AND 30
               WHEN 'CLOSURE_CONFIRMATION_DAYS' THEN value BETWEEN 1 AND 30
               WHEN 'CANCELLATION_CONFIRMATION_DAYS' THEN value BETWEEN 1 AND 30
               WHEN 'AUTOMATIC_CLOSURE_DAYS' THEN value BETWEEN 1 AND 30
               WHEN 'FINAL_MERGE_AUTHORIZATION_DAYS' THEN value BETWEEN 1 AND 30
               ELSE FALSE
        END)
);

GRANT SELECT, UPDATE ON settings.rule_value TO ${runtime_role};

INSERT INTO settings.rule_value (rule_key, value, updated_at)
VALUES ('AUTO_ACCEPTANCE_DAYS', 7, now()),
       ('AUTO_ACCEPTANCE_FIRST_REMINDER_DAY', 3, now()),
       ('AUTO_ACCEPTANCE_SECOND_REMINDER_DAY', 6, now()),
       ('RATING_WINDOW_DAYS', 14, now()),
       ('REVISION_ROUNDS', 2, now()),
       ('DISPUTE_RESPONSE_DAYS', 5, now()),
       ('TEAM_ISSUE_ANSWER_DAYS', 5, now()),
       ('TEAM_ISSUE_ESCALATION_DAYS', 5, now()),
       ('CLOSURE_CONFIRMATION_DAYS', 5, now()),
       ('CANCELLATION_CONFIRMATION_DAYS', 5, now()),
       ('AUTOMATIC_CLOSURE_DAYS', 7, now()),
       ('FINAL_MERGE_AUTHORIZATION_DAYS', 7, now()),
       ('MENTOR_ACTIVE_JOB_LIMIT', 3, now()),
       ('MENTOR_SHARE_CAP_PERCENT', 40, now());

-- The school domains whose addresses may receive a verification code; a subdomain of an entry is
-- allowed too. Empty until OQ8 decides the real list; the dev seed adds a few.
CREATE TABLE settings.school_domain
(
    domain   TEXT PRIMARY KEY CHECK (domain ~ '^[a-z0-9](?:[a-z0-9-]*[a-z0-9])?(?:\.[a-z0-9](?:[a-z0-9-]*[a-z0-9])?)+$'),
    added_at TIMESTAMPTZ NOT NULL
);

GRANT SELECT, INSERT, DELETE ON settings.school_domain TO ${runtime_role};
