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

	/** The Rating as an escalated Team Issue's Administrator reads it (FR-63, FR-79), hidden or not. */
	record RatingEvidence(UUID ratingId, int stars, String comment, Instant createdAt, @Nullable Instant hiddenAt) {
	}

	/** The visible Ratings the Student received, the newest first. */
	List<CrewRating> receivedBy(UUID studentId);

	/** The Rating while it is visible; empty otherwise. */
	Optional<CrewRating> find(UUID ratingId);

	/**
	 * {@code reputation.removeRating}: a Team Issue's outcome removes the Rating (FR-49, FR-79), which is
	 * hidden with {@code reason} for its rater and stops counting; {@link RatingRemoved} is published.
	 * @return whether it was visible until now
	 */
	boolean removeRating(UUID ratingId, String reason);

	/**
	 * The Team Evidence Package's Rating (AD-40): read by the Team Issue's assigned Administrator, which
	 * {@code dispute} checks, and audited {@code reputation.admin_read} (AD-8).
	 */
	Optional<RatingEvidence> evidence(UUID ratingId, UUID adminId);

}
