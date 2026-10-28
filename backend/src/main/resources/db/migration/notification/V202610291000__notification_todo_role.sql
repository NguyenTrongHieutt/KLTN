-- Story 20.6 (AD-27, UX-DR86, UX-DR88): every to-do of a Job carries the recipient's role in that Job, so the
-- strip and CUS-1 say "{job} · Bạn là {role}" and CUS-7 counts each role's open to-dos. The role is stored when
-- the to-do opens, from the audience class the opening module confirmed: CUSTOMER, MENTOR, or CREW for each
-- Crew class. A SUBJECT or ADMIN to-do has none, and neither has an account to-do.

ALTER TABLE notification.todo
    ADD COLUMN role TEXT CHECK (role IN ('CUSTOMER', 'MENTOR', 'CREW')),
    ADD CONSTRAINT todo_role_job_check CHECK (role IS NULL OR job_id IS NOT NULL);

-- The rows already open: a key with one audience class in the registry names its role. A key with several
-- (scanning.resolve_work_finding, dispute.submit_statement, repo.accept_github_invitation,
-- repo.confirm_data_deletion, job.answer_cancellation, job.answer_change_request, reputation.rate,
-- job.answer_closure) cannot tell from the row, so those stay without one.
UPDATE notification.todo SET role = 'CUSTOMER'
WHERE job_id IS NOT NULL
  AND todo_key IN ('matching.choose_assignee', 'repo.reconfirm_source', 'repo.revocation_item', 'payment.fund_top_up',
                   'repo.authorize_final_merge', 'job.review_agreement', 'payment.fund_milestone',
                   'reputation.approve_portfolio_summary', 'delivery.approve_follow_ups', 'job.grant_external_access',
                   'delivery.review_handover');

UPDATE notification.todo SET role = 'MENTOR'
WHERE job_id IS NOT NULL
  AND todo_key IN ('matching.sign_nda', 'dispute.answer_team_issue', 'job.draft_agreement', 'job.revise_agreement',
                   'job.confirm_agreement_mentor', 'job.pick_crew', 'work.review_work_item', 'work.reassign_work_items',
                   'reputation.draft_portfolio_summary', 'delivery.draft_follow_ups', 'job.add_change_impact',
                   'delivery.hand_over');

UPDATE notification.todo SET role = 'CREW'
WHERE job_id IS NOT NULL
  AND todo_key IN ('job.confirm_crew_terms', 'work.fix_returned_item', 'dispute.decide_team_issue_answer',
                   'reputation.give_private_feedback');
