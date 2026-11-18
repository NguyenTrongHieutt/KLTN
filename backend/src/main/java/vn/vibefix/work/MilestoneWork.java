package vn.vibefix.work;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import vn.vibefix.shared.WorkItemKey;

/**
 * What a Milestone Handover reads from the Board (FR-10, FR-32, AD-39): each Work Item of the Milestone
 * and whether it reached "Xong" through the Mentor's approval. A Code Job's handover also links its
 * commits to the Work Items their messages mention (FR-23). Nothing here is for a Customer screen
 * (AD-39).
 */
public interface MilestoneWork {

	/**
	 * One Work Item of the Milestone.
	 * @param key {@code {JOBCODE}-{n}}
	 * @param status {@code TODO}, {@code IN_PROGRESS}, {@code IN_REVIEW}, or {@code DONE}
	 * @param approved in "Xong" and its latest review is the Mentor's approval, or their own unreviewed move (R8)
	 */
	record ItemState(UUID id, String key, String title, String status, boolean approved) {
	}

	/** The Milestone's Work Items in key order. */
	List<ItemState> items(UUID jobId, UUID milestoneId);

	/** A commit and the Work Item keys its message mentions ({@link WorkItemKey#mentionedIn}). */
	record CommitMention(String sha, List<WorkItemKey> keys) {

		public CommitMention {
			keys = List.copyOf(keys);
		}

	}

	/**
	 * Links each commit to the Work Items of the Job's Board it mentions, in the caller's transaction; a
	 * key of another Board, or one no Work Item carries, links nothing, and a link made before stays.
	 * @return the keys linked, by commit SHA
	 */
	Map<String, List<String>> linkCommits(UUID jobId, UUID handoverId, List<CommitMention> mentions);

}
