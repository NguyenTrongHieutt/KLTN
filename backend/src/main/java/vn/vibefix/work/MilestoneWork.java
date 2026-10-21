package vn.vibefix.work;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import vn.vibefix.shared.WorkItemKey;

/**
 * What a Milestone Handover reads from the Board (FR-10, FR-32, AD-39): each Work Item of the Milestone
 * and whether it reached "Xong" through the Mentor's approval, the Assessment Report's versions, which
 * never change once written, and the Pages updated during the Milestone. A Code Job's handover also
 * links its commits to the Work Items their messages mention (FR-23). Nothing here is for a Customer
 * screen except the Report's text and the customer-visible Pages.
 */
public interface MilestoneWork {

	/**
	 * One Work Item of the Milestone.
	 * @param key {@code {JOBCODE}-{n}}
	 * @param status {@code TODO}, {@code IN_PROGRESS}, {@code IN_REVIEW}, or {@code DONE}
	 * @param approved in "Xong" and its latest review is the Mentor's approval
	 */
	record ItemState(UUID id, String key, String title, String status, boolean approved) {
	}

	/** One version of the Assessment Report Page. */
	record ReportVersion(UUID pageId, int versionNo, String title, String body) {
	}

	/** The Milestone's Work Items in key order. */
	List<ItemState> items(UUID jobId, UUID milestoneId);

	/** The Job's Assessment Report at its current version, when it has one. */
	Optional<ReportVersion> currentReport(UUID jobId);

	/** One version of the Assessment Report, such as the one a handover pinned. */
	Optional<ReportVersion> report(UUID pageId, int versionNo);

	/** A Page with a version saved since a time; {@code customerVisible} for the Customer's list. */
	record PageUpdate(UUID pageId, String title, int versionNo, boolean customerVisible) {
	}

	/** The Job's Pages that are not hidden and have a version saved after {@code since}, by title. */
	List<PageUpdate> pagesUpdatedSince(UUID jobId, Instant since);

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
