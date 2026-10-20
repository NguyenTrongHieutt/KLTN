package vn.vibefix.work;

import java.util.Map;
import java.util.UUID;

/**
 * Facts from a Job's Board for its Portfolio Entries (FR-50, AD-33), read from the Work Item histories,
 * never typed by anyone.
 */
public interface WorkFacts {

	/** Per assignee: the Work Items in "Xong" whose latest review is the Mentor's approval. */
	Map<UUID, Integer> approvedItems(UUID jobId);

	/** How many reviews (approvals and returns) the account wrote on the Job's Board. */
	int reviewsWritten(UUID jobId, UUID accountId);

}
