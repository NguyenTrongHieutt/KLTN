-- Story 19.2 (AD-10, UX-DR91): "sign in again" became "re-link GitHub". The to-dos and notifications
-- already sent under identity.sign_in_again take the new key, and lead to ACC-2 instead of the
-- /dang-nhap-lai page, which now redirects there.

UPDATE notification.todo
SET todo_key = 'identity.relink_github', link = '/ho-so/xac-minh'
WHERE todo_key = 'identity.sign_in_again';

UPDATE notification.notification
SET type = 'identity.relink_github', link = '/ho-so/xac-minh'
WHERE type = 'identity.sign_in_again';
