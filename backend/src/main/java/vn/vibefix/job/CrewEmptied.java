package vn.vibefix.job;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

import org.jspecify.annotations.Nullable;

import vn.vibefix.shared.DomainEvent;

/**
 * A release left a Job with no counted {@code ACTIVE} Crew row (AD-41, R7, Story 21.5): a lock counts as leaving.
 * {@code matching} asks the Mentor again to recruit ({@code matching.open_crew_recruitment}) while the Agreement is not
 * confirmed, and the review to-do returns to its tier for a Crew with no member.
 * @param state the Job state's name when the Crew emptied
 * @param agreementConfirmed the Mentor had confirmed the Agreement
 * @param agreementDueAt the Agreement deadline, the open-recruitment to-do's
 */
public record CrewEmptied(UUID eventId, UUID jobId, UUID mentorId, String state, boolean agreementConfirmed,
		@Nullable Instant agreementDueAt) implements DomainEvent {

	public CrewEmptied {
		Objects.requireNonNull(eventId, "eventId");
		Objects.requireNonNull(jobId, "jobId");
		Objects.requireNonNull(mentorId, "mentorId");
		Objects.requireNonNull(state, "state");
	}

}
