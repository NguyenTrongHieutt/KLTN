-- Schema repo (AD-46, R30, Story 23.8b): the Milestone a handover copy hands over, by its number. A copy run that
-- failed or timed out alerts every Administrator (github.runner_run_failed, "Lần sao chép bàn giao trên kho chạy
-- thất bại: {job}, Giai đoạn {n}"), and the alert is raised where the copy resolves, in repo, which until now kept
-- no Milestone of its own. The number comes from the request delivery makes, beside the pull request's title.
-- Old rows: the copies of Story 23.8a, whose Milestone was never recorded; they keep 0, which the alert reads as
-- "unknown" and no new copy ever writes.
-- Personal data: none.

ALTER TABLE repo.handover_copy
    ADD COLUMN milestone_number INTEGER NOT NULL DEFAULT 0 CHECK (milestone_number >= 0);
