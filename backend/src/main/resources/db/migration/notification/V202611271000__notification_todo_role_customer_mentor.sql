-- Story 25.4 (AD-47 exception 12 c): a to-do opened to the Customer of a Self-mentored Job records the one role
-- CUSTOMER_MENTOR, whichever audience class (CUSTOMER or MENTOR) it was opened to, so the strip says "Bạn là Khách
-- hàng · tự hướng dẫn" and CUS-7 counts it under the Jobs the person posted. Rows already open keep the role they
-- were opened with: this schema cannot read a Job's mode.

ALTER TABLE notification.todo
    DROP CONSTRAINT todo_role_check,
    ADD CONSTRAINT todo_role_check CHECK (role IN ('CUSTOMER', 'MENTOR', 'CREW', 'CUSTOMER_MENTOR'));
