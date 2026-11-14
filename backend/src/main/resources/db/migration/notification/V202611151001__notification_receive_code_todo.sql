-- Story 23.10a (FR-37, FR-38, D10, UX-DR118): the Final Merge authorization became the closing choice, so the
-- Customer's to-do repo.authorize_final_merge is now repo.receive_code, "Chọn cách nhận mã của {job}".
--
-- The rows are renamed rather than closed, because a Job already in Closing keeps its tier-1 to-do: J8 has passed and
-- would not open it again. The new text names the Job, which the old rows have no parameter for, so the title is
-- filled from the Job the row already names -- the one place it is kept. A row whose Job is gone has no title to name
-- and would show a raw placeholder, so it goes.
--
-- The 2-day reminder repo.receive_code_reminder is scheduled only inside FinalMergeAuthorizations.closing, which J8
-- has already run for these Jobs: a Job in Closing today keeps its renamed to-do and its FR-37 deadline, and never
-- gets a reminder row. Nothing is backfilled into timers.deadline -- a reminder inserted for a deadline already
-- running would fire at a time no J8 chose -- and the deadline itself still decides how the Job ends.
-- Personal data: nothing new is kept; the rows keep the recipient they had.

UPDATE notification.todo AS todo
SET todo_key = 'repo.receive_code',
    params   = jsonb_build_object('job', job.title)
FROM job.job AS job
WHERE todo.todo_key = 'repo.authorize_final_merge'
  AND job.id = todo.job_id;

DELETE FROM notification.todo WHERE todo_key = 'repo.authorize_final_merge';
