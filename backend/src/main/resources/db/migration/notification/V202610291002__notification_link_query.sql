-- Story 20.6 (UX-DR86): a notification or a to-do may link to a segment of "Công việc", such as
-- /cong-viec?vai-tro=tham-gia, so a platform path may carry one query parameter. The rows already stored keep
-- their links.

ALTER TABLE notification.notification
    DROP CONSTRAINT notification_link_check,
    ADD CONSTRAINT notification_link_check CHECK (link ~ '^/[a-z0-9/-]*(\?[a-z0-9-]+=[a-z0-9-]+)?$');

ALTER TABLE notification.todo
    DROP CONSTRAINT todo_link_check,
    ADD CONSTRAINT todo_link_check CHECK (link ~ '^/[a-z0-9/-]*(\?[a-z0-9-]+=[a-z0-9-]+)?$');
