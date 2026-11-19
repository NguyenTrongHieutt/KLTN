-- Story 24.2b (D1, FR-76 removed, NFR-3, AD-21, AD-24): Attachments are retired with the Board, so the read policy
-- that named the purpose work.attachment is deleted with the module and GET /api/v1/files/{id} would answer 404 for
-- such a row in any case. The rows are marked deleted, as Story 19.7's student cards (V202610261203) and Story
-- 23.10d's final ZIPs (V202611170900) were, so nothing serves, exports or counts them and the uploader can still
-- tell that the deletion happened.
--
-- A files row is never deleted outright (V202610192000:10), only marked. The bytes on the volume are not this
-- migration's to remove: StoredFiles.delete deletes them before it marks the row, and only running code can reach
-- the volume, so the bytes of any such row stay on the dev volume until the author's reset removes them. The Job's
-- 200 MB quota counted these rows (NFR-3); a marked row counts for nothing, and nothing uploads under this purpose
-- any more.
--
-- What stays: every other purpose's rows, untouched, the Mentor's handover screenshots among them.
-- Personal data: the marked rows were a Mentor's or a Crew member's own upload on a Work Item; nothing new is kept.

UPDATE files.stored_file SET deleted_at = now()
WHERE purpose = 'work.attachment' AND deleted_at IS NULL;
