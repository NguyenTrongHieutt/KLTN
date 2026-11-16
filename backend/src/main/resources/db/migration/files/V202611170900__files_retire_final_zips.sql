-- Story 23.10d (FR-21, FR-38, D10, AD-21, AD-24): the final ZIP of Story 7.7 is retired. Nothing builds,
-- stores or serves a ZIP of vibefix's own any more -- the 30-day deletion simply deletes both repositories --
-- and the read policy that named purpose repo.final_zip is deleted with it, so GET /api/v1/files/{id} would
-- answer 404 for such a row in any case. The rows are marked deleted, as Story 19.7's student cards and Mentor
-- application documents were (V202610261203), so nothing serves, exports or counts them and the owner can still
-- tell that the deletion happened.
--
-- A files row is never deleted outright (V202610192000:10), only marked. The bytes on the volume are not this
-- migration's to remove: StoredFiles.delete deletes them before it marks the row, and only running code can
-- reach the volume, so the bytes of any such row stay on the dev volume until the author's reset removes them.
-- The dev database holds no row of this purpose today, so this is a statement about correctness, not a clean-up.
--
-- What stays: FileKind.ARCHIVE and the archive media types, because a person may still upload an archive; and
-- every other purpose's rows, untouched.
-- Personal data: the marked rows were a Customer's own copy of their code; nothing new is kept.

UPDATE files.stored_file SET deleted_at = now()
WHERE purpose = 'repo.final_zip' AND deleted_at IS NULL;
