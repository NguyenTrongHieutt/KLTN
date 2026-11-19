package vn.vibefix.job;

import java.util.UUID;

/**
 * The Job's upload quota (AD-34): its own screenshots and the files other modules attach to it, the Mentor's
 * handover screenshots among them (FR-32), share {@value #QUOTA_BYTES} bytes.
 */
public interface JobUploads {

	/** 200 MB per Job. */
	long QUOTA_BYTES = 200L * 1024 * 1024;

	/** The bytes of the Job's screenshots. */
	long screenshotBytes(UUID jobId);

}
