-- Story 22.4 (FR-79, AD-40, SG-63): a Team Issue is about a Rating only; a share disagreement is a Split Resolution.
-- The share issues go, decided ones included: only dev data holds them, and the audit trail keeps what happened. Their
-- Deadlines end OBSOLETE by their handlers, which close the issues' to-dos. The subject stays as a column holding RATING
-- only, the Milestone and the points go with the checks that named them, and an answer no longer transfers points.
-- Personal data: rows deleted; nothing new is kept.

DELETE FROM dispute.team_issue WHERE subject = 'SHARE';

DROP INDEX dispute.team_issue_share_idx;

ALTER TABLE dispute.team_issue
    DROP COLUMN milestone_id,
    DROP COLUMN milestone_number,
    DROP COLUMN answer_points,
    DROP COLUMN outcome_points,
    DROP COLUMN decision_points,
    DROP CONSTRAINT team_issue_subject_check,
    DROP CONSTRAINT team_issue_answer_kind_check;

ALTER TABLE dispute.team_issue
    ALTER COLUMN subject SET DEFAULT 'RATING',
    ALTER COLUMN rating_id SET NOT NULL,
    ADD CONSTRAINT team_issue_subject_check CHECK (subject = 'RATING'),
    ADD CONSTRAINT team_issue_answer_kind_check CHECK (answer_kind IN ('KEEP', 'WITHDRAW_RATING'));
