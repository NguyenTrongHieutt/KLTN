-- FR-61, FR-81, FR-83 (Story 21.5): a picked person reads the split rules before signing, so the split rule values
-- that did not exist yet are added with the PRD's FR-83 defaults: the split draft deadline (7 days after the
-- Milestone's release, 1 to 30), the split confirmation deadline (3 days after each saved version, 1 to 30) and the
-- split edit limit (3 versions after the first per Milestone, 1 to 10). The Mentor share cap exists since Story 4.3.
-- An Administrator may change each on ADM-10, audited like every rule, and each is copied onto a Job when it is
-- published (J1). Story 22.1 makes the Milestone Splits that use them.
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
    ELSE FALSE
END);

INSERT INTO settings.rule_value (rule_key, value, updated_at)
VALUES ('SPLIT_DRAFT_DAYS', 7, now()),
       ('SPLIT_CONFIRMATION_DAYS', 3, now()),
       ('SPLIT_EDIT_LIMIT', 3, now());
