-- Story 20.1 (AD-19): a session now holds only the account's ID and kind, never its role. Every stored
-- session holds the old serialized authentication, which the application can no longer read, so all of
-- them go and everyone signs in again once. A request that still sends an old session cookie finds no
-- session and continues as anonymous.

DELETE FROM platform.spring_session_attributes;
DELETE FROM platform.spring_session;
