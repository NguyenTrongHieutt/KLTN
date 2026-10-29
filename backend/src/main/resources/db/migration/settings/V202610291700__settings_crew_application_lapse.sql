-- FR-61, R6 (Story 21.4): how many days a Crew Application waits for the Mentor before it lapses becomes a rule value
-- an Administrator may change on ADM-10 (1 to 30 days, 14 by default), audited like every rule. Like every rule, it is
-- copied onto a Job when it is published (J1), so a running Job keeps the value it was published with; a Job published
-- before this rule existed lapses its applications after the default.
-- Personal data: none.

ALTER TABLE settings.rule_value DROP CONSTRAINT rule_value_check;

ALTER TABLE settings.rule_value ADD CONSTRAINT rule_value_check CHECK (CASE rule_key
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
    WHEN 'AGREEMENT_STALL_DAYS' THEN value BETWEEN 7 AND 30
    WHEN 'STARTING_STALL_DAYS' THEN value BETWEEN 3 AND 30
    WHEN 'CREW_APPLICATION_LAPSE_DAYS' THEN value BETWEEN 1 AND 30
    ELSE FALSE
END);

INSERT INTO settings.rule_value (rule_key, value, updated_at)
VALUES ('CREW_APPLICATION_LAPSE_DAYS', 14, now());
