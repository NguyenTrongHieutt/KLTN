package vn.vibefix.job;

import java.util.Objects;
import java.util.UUID;

import vn.vibefix.shared.DomainEvent;

/**
 * The Mentor turned a Job's Crew recruitment on (AD-41, UX-DR112, Story 21.4): {@code matching} closes the Mentor's
 * {@code matching.open_crew_recruitment} to-do, which J4 opened.
 */
public record CrewRecruitmentOpened(UUID eventId, UUID jobId) implements DomainEvent {

	public CrewRecruitmentOpened {
		Objects.requireNonNull(eventId, "eventId");
		Objects.requireNonNull(jobId, "jobId");
	}

}
