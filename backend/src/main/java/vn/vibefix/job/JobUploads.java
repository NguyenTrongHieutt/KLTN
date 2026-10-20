package vn.vibefix.job;

import java.util.UUID;

/**
 * The Job's upload quota (AD-34): its screenshots and the files other modules attach to it, such as
 * Work Item Attachments (FR-76), share {@value #QUOTA_BYTES} bytes.
 */
public interface JobUploads {

	/** 200 MB per Job. */
	long QUOTA_BYTES = 200L * 1024 * 1024;

	/** The bytes of the Job's screenshots. */
	long screenshotBytes(UUID jobId);

}
