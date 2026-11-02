package vn.vibefix.chat;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.jspecify.annotations.Nullable;

/**
 * What a decided Dispute's evidence snapshot records of a Job Chat (AD-31, SG-27): how far it reached when
 * the decision was made, never a message's text. A Split Resolution's Evidence Package reads the Crew Chat of the
 * Milestone's interval under its grant, and its decision records how far that reached (AD-40).
 */
public interface ChatEvidence {

	/** @param lastMessageId the newest message, or {@code null} when there is none */
	record ChatExtent(int messageCount, @Nullable UUID lastMessageId, @Nullable Instant lastSentAt) {
	}

	/** The Job Chat's extent; no messages when the Job has no Job Chat. */
	ChatExtent jobChat(UUID jobId);

	/**
	 * One Crew Chat message.
	 * @param attachmentName the name of a file sent with it
	 */
	record CrewMessage(UUID messageId, UUID authorId, String body, @Nullable String attachmentName, Instant sentAt) {
	}

	/**
	 * The Crew Chat between two instants, oldest first, for an Administrator under a grant naming it, audited
	 * {@code chat.admin_read} (AD-8, AD-20); no messages when the Job has no Crew Chat any more.
	 * @param from the start, both ends included; empty for a split older than Story 22.3 whose Milestone had gone
	 * @throws vn.vibefix.shared.ApiException {@code NOT_FOUND} unless an open grant of theirs covers the Crew Chat
	 */
	List<CrewMessage> crewChatUnderGrant(UUID adminId, UUID jobId, @Nullable Instant from, Instant to);

	/** How far the Crew Chat reached between two instants, for a decision's record, never a message's text. */
	ChatExtent crewChat(UUID jobId, @Nullable Instant from, Instant to);

}
