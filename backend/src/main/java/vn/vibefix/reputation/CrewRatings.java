package vn.vibefix.reputation;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.jspecify.annotations.Nullable;

/**
 * The Ratings a Mentor gave the Crew members (FR-49) as a Team Issue reads them (FR-79, AD-40): only a
 * visible one, published with the Crew's Ratings and not hidden, may be contested.
 */
public interface CrewRatings {

	/**
	 * One visible {@code MENTOR_TO_STUDENT} Rating.
	 * @param mentorId the rater; {@code null} once the Mentor was erased
	 * @param visibleAt when the Crew's Ratings were published
	 */
	record CrewRating(UUID ratingId, UUID jobId, String jobTitle, @Nullable UUID mentorId, UUID studentId, int stars,
			Instant visibleAt) {
	}

	/** The visible Ratings the Student received, the newest first. */
	List<CrewRating> receivedBy(UUID studentId);

	/** The Rating while it is visible; empty otherwise. */
	Optional<CrewRating> find(UUID ratingId);

}
