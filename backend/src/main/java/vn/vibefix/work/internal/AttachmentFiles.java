package vn.vibefix.work.internal;

import java.util.UUID;

import org.springframework.stereotype.Component;

import vn.vibefix.files.FileReadPolicy;
import vn.vibefix.shared.ApiException;
import vn.vibefix.shared.Caller;
import vn.vibefix.work.internal.Attachments.Attachment;
import vn.vibefix.work.internal.Boards.Item;
import vn.vibefix.work.internal.WorkAccess.BoardViewer;

/**
 * Who downloads an Attachment's file (FR-76, AD-20, AD-39, AD-41): whoever reads its Work Item and sees
 * the Attachment there. The Mentor and the active Crew get files not removed, a former member only what
 * was there at the end of their window, and an Administrator under a {@code WORK_ITEMS} grant any of
 * the Work Item's files, the read audited. The Customer never gets one.
 */
@Component
class AttachmentFiles implements FileReadPolicy {

	private final Attachments attachments;

	private final Boards boards;

	private final WorkAccess access;

	private final WorkReads reads;

	AttachmentFiles(Attachments attachments, Boards boards, WorkAccess access, WorkReads reads) {
		this.attachments = attachments;
		this.boards = boards;
		this.access = access;
		this.reads = reads;
	}

	@Override
	public String purpose() {
		return Attachments.PURPOSE;
	}

	@Override
	public boolean mayRead(UUID fileId, Caller caller) {
		Attachment attachment = this.attachments.byFile(fileId).orElse(null);
		Item item = attachment == null ? null : this.boards.item(attachment.itemId()).orElse(null);
		if (attachment == null || item == null) {
			return false;
		}
		try {
			BoardViewer viewer = this.access.reader(item.jobId(), caller);
			this.reads.item(viewer, item);
			return this.reads.sees(viewer, attachment);
		}
		catch (ApiException notFound) {
			return false;
		}
	}

}
