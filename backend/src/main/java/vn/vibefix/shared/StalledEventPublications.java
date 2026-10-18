package vn.vibefix.shared;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Event publications that failed 5 attempts (AD-9). They are never resubmitted automatically; they
 * wait in the Administrator queue (FR-64), whose screen arrives with its own story.
 */
public interface StalledEventPublications {

	/** One stalled publication; it holds no payload, which may name accounts. */
	record StalledPublication(UUID publicationId, String eventType, String listenerId, Instant publishedAt,
			int attempts, Instant stalledAt) {
	}

	/** Every stalled publication, oldest first. */
	List<StalledPublication> findAll();

}
