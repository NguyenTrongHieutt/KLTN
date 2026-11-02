-- FR-61, UX-DR110 (Story 22.2): the Mentor share cap's default becomes 30 where it still is the old 40, so an
-- Administrator's own choice stays, and the Split Resolution's undecided flag gets its value (1 to 30, default 7), which
-- nothing reads until Story 22.3. An Administrator may change it on ADM-10, audited like every rule, and J1 copies it
-- onto a Job when it is published.
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
    WHEN 'SPLIT_DRAFT_DAYS' THEN value BETWEEN 1 AND 30
    WHEN 'SPLIT_CONFIRMATION_DAYS' THEN value BETWEEN 1 AND 30
    WHEN 'SPLIT_EDIT_LIMIT' THEN value BETWEEN 1 AND 10
    WHEN 'SPLIT_UNDECIDED_FLAG_DAYS' THEN value BETWEEN 1 AND 30
    ELSE FALSE
END);

INSERT INTO settings.rule_value (rule_key, value, updated_at)
VALUES ('SPLIT_UNDECIDED_FLAG_DAYS', 7, now());

UPDATE settings.rule_value
SET value = 30,
    updated_at = now()
WHERE rule_key = 'MENTOR_SHARE_CAP_PERCENT'
  AND value = 40;
