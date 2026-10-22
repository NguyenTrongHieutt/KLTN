package vn.vibefix.chat;

import java.time.Instant;
import java.util.UUID;

import org.jspecify.annotations.Nullable;

/**
 * What a decided Dispute's evidence snapshot records of a Job Chat (AD-31, SG-27): how far it reached when
 * the decision was made, never a message's text.
 */
public interface ChatEvidence {

	/** @param lastMessageId the newest message, or {@code null} when there is none */
	record ChatExtent(int messageCount, @Nullable UUID lastMessageId, @Nullable Instant lastSentAt) {
	}

	/** The Job Chat's extent; no messages when the Job has no Job Chat. */
	ChatExtent jobChat(UUID jobId);

}
