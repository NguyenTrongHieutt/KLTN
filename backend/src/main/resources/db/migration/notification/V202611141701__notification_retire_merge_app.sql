-- Story 23.9a (FR-37, FR-48, R1, D7, UX-DR118): the Final Merge runs on the Customer's own GitHub Actions, so there
-- is no app to install at the end. The four Merge-App notifications (the authorization pending, its reminder, an
-- organization owner's later approval, and a request no owner approved) and the widened-selection warning have no
-- type and no text any more. Their rows go, open or closed, read or not, so no raw key reaches a screen.
-- repo.final_merge_completed stays, because the Jobs that ended before this story really were merged by vibefix;
-- repo.final_branch_ready takes its place from now on.
-- Personal data: rows deleted; nothing new is kept.

DELETE FROM notification.notification
WHERE type IN ('repo.final_merge_authorization_pending', 'repo.final_merge_authorization_reminder',
               'repo.final_merge_authorized', 'repo.merge_app_request_refused', 'repo.merge_app_selection_widened');
