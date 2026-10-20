package vn.vibefix.work;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * What a Milestone Handover reads from the Board (FR-10, FR-32, AD-39): each Work Item of the Milestone
 * and whether it reached "Xong" through the Mentor's approval, and the Assessment Report's versions,
 * which never change once written. Nothing here is for a Customer screen except the Report's text.
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

}
