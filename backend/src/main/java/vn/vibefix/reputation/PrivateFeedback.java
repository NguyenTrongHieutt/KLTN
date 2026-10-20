package vn.vibefix.reputation;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Private feedback about Mentors (FR-78, FR-62, AD-20), for Administrators only (ADM-1, ADM-2, Story
 * 10.1); no Mentor, Customer, or Crew surface ever reads it.
 */
public interface PrivateFeedback {

	/** One Mentor's private feedback: average to one decimal, count, and the comments given. */
	record Summary(UUID mentorId, BigDecimal average, int count, List<String> comments) {
	}

	/** Statistics across Mentors, only when there are enough to keep each author unseen. */
	record Statistics(BigDecimal average, int count, int mentors) {
	}

	/** The Mentor's summary, when anyone gave them feedback. */
	Optional<Summary> summary(UUID mentorId);

	/** Mentors averaging below 3 over at least 3 feedbacks (ADM-1's filter). */
	List<Summary> belowThreshold();

	/** The overall figure, only with at least 5 feedbacks across at least 3 Mentors (FR-62). */
	Optional<Statistics> statistics();

}
