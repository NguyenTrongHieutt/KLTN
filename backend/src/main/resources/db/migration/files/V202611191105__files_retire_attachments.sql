-- Story 24.2b (D1, FR-76 removed, AD-21, AD-24): Attachments are retired with the Board, so the read policy that
-- named the purpose work.attachment is deleted with the module and GET /api/v1/files/{id} would answer 404 for such
-- a row in any case. The rows are marked deleted, as Story 19.7's student cards (V202610261203) and Story 23.10d's
-- final ZIPs (V202611170900) were, so nothing serves or exports them, and the uploader can still tell that the
-- deletion happened. A files row is never deleted outright (V202610192000:10), only marked.
--
-- The bytes are NOT removed by this story, and nothing else in it removes them. JdbcStoredFiles.delete is the only
-- code that deletes a file from the volume, and both callers that ever passed it a work.attachment row --
-- AttachmentFiles and WorkPersonalData -- are deleted in this same change, so marking the row is all that is left
-- to do here. The removal of the orphaned bytes is recorded as deferred work; only a full account erasure reaches
-- them meanwhile. The author's dev database holds no row of this purpose today, so this statement is a matter of
-- correctness rather than a clean-up, and a sweeper for no rows would be the wrong trade.
--
-- Neither quota changes with the mark. NFR-3's hourly upload quota is per account and counts deleted rows on
-- purpose: JdbcStoredFiles.requireQuota sums files.stored_file by account_id and stored_at with no deleted_at
-- filter, so a marked row still counts against its uploader for that hour. FR-76's 200 MB Job limit never read
-- files.stored_file at all -- Attachments summed work.attachment.size_bytes per job_id against
-- JobUploads.QUOTA_BYTES -- and this story drops that table with the schema, so no Job total counts these rows
-- either before or after. What still counts against JobUploads.QUOTA_BYTES is the Customer's Draft screenshots
-- (Jobs.addScreenshot) and the Mentor's handover screenshots (HandoverScreenshots.add), neither of which this
-- statement touches.
--
-- What stays: every other purpose's rows, untouched, the Mentor's handover screenshots among them.
-- Personal data: the marked rows were a Mentor's or a Crew member's own upload on a Work Item; nothing new is kept.

UPDATE files.stored_file SET deleted_at = now()
WHERE purpose = 'work.attachment' AND deleted_at IS NULL;
