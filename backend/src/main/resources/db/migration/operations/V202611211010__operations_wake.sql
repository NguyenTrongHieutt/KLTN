-- Story 24.5a (AD-6, AD-46): an operation that waits for a GitHub Actions run defers to its backstop only, and the
-- run's completion event, its report or its Deadline wakes it (ExternalOperations.wake). A pending operation is made
-- due at once. A running one cannot be: its attempt may have read the run just before it completed, and would then
-- defer past the wake. So the wake is kept here, the attempt's deferral reads it and is due at once instead, and
-- the next claim forgets it. Existing rows start with no wake.
-- Personal data: none; a time, when something outside woke the operation.

ALTER TABLE operations.external_operation ADD COLUMN woken_at TIMESTAMPTZ;
