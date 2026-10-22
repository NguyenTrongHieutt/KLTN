package vn.vibefix.work;

import java.util.List;
import java.util.UUID;

/**
 * What a decided Dispute's evidence snapshot records of a Job's Board (AD-31, SG-27): the IDs and versions
 * of its Work Items and Pages, never their text.
 */
public interface WorkEvidence {

	/** @param version the Work Item's edit version (AD-23) */
	record ItemLine(UUID workItemId, int number, long version, String status) {
	}

	record PageLine(UUID pageId, int versionNo) {
	}

	/** The Job's Work Items, by number. */
	List<ItemLine> workItems(UUID jobId);

	/** The Job's Pages at their current version, oldest first. */
	List<PageLine> pages(UUID jobId);

}
