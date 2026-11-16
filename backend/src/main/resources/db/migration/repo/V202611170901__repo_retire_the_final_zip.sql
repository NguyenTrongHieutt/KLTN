-- Story 23.10d (FR-21, FR-38, D10, AD-8, NFR-10): the final ZIP of Story 7.7 is retired. The 30-day deletion no
-- longer archives anything first -- it deletes the Working Repository, then the Intermediary, and reads both back
-- as gone -- so repo.job_setup.archive_file_id, added by V202610221740 to name the stored archive, has no reader
-- and no writer left. The column goes; the files rows it named are marked deleted by V202611170900, a minute
-- earlier, because data goes before schema.
--
-- What stays: everything else on the Setup -- the two repositories, the default branch, started_at and
-- deleted_at -- because the deletion still needs them and a Setup is the Job's record of what was created on
-- GitHub. The audit entries repo.final_zip_stored and repo.deletion_started that were written in the past stay
-- too: the audit trail is never rewritten (AD-8), and they say truthfully what happened then.
--
-- A repo.repository_delete operation queued before this story carries a zip flag and a customerId in its payload.
-- Neither is read any more, and neither is removed here: the payload is the operation's own record, and the
-- handler deletes exactly the repositories that payload names.
-- Personal data: the column held a files row's id, and that row is marked deleted by this story's files
-- migration; nothing new is kept.

ALTER TABLE repo.job_setup DROP COLUMN IF EXISTS archive_file_id;
