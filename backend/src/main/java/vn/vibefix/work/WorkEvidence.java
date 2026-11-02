package vn.vibefix.work;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.jspecify.annotations.Nullable;

/**
 * What a decided Dispute's evidence snapshot records of a Job's Board (AD-31, SG-27): the IDs and versions
 * of its Work Items and Pages, never their text. A Split Resolution's Evidence Package reads a Milestone's Work Items with
 * their histories under its grant, and its decision records their IDs and versions (AD-40).
 */
public interface WorkEvidence {

	/** @param version the Work Item's edit version (AD-23) */
	record ItemLine(UUID workItemId, int number, long version, String status) {
	}

	record PageLine(UUID pageId, int versionNo) {
	}

	/** The Job's Work Items, by number. */
	List<ItemLine> workItems(UUID jobId);

	/**
	 * One entry of a Work Item's history.
	 * @param action what happened, such as {@code SUBMITTED}, {@code APPROVED}, {@code RETURNED} or {@code NOTE}
	 * @param actorId who did it, while an erasure has not anonymized them
	 * @param comment a submission's note, a review's comment, or a note's text
	 */
	record HistoryLine(String action, @Nullable UUID actorId, @Nullable String comment, Instant at) {
	}

	/**
	 * A Work Item with its history (FR-84).
	 * @param key its {@code {JOBCODE}-{n}} key
	 * @param assigneeId its assignee, while an erasure has not anonymized them
	 */
	record PartyItem(UUID workItemId, String key, String title, String status, @Nullable UUID assigneeId,
			List<HistoryLine> history) {
	}

	/**
	 * The Milestone's Work Items an open grant of the Administrator covers, by number, with their histories, audited
	 * {@code work.admin_read} (AD-8, AD-20): a Split Resolution's grant covers those of the split's parties.
	 * @throws vn.vibefix.shared.ApiException {@code NOT_FOUND} unless they hold such a grant on the Job
	 */
	List<PartyItem> milestoneItemsUnderGrant(UUID adminId, UUID jobId, UUID milestoneId);

	/** The Milestone's Work Items that involve one of these accounts, by number, for a decision's record. */
	List<ItemLine> milestoneItems(UUID jobId, UUID milestoneId, List<UUID> accounts);

	/** The Job's Pages at their current version, oldest first. */
	List<PageLine> pages(UUID jobId);

}
