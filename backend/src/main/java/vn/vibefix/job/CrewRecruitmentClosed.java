package vn.vibefix.job;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

import vn.vibefix.job.CrewRecruitment.ClosedBy;
import vn.vibefix.shared.DomainEvent;

/**
 * A Job's Crew recruitment closed (AD-7, AD-41, R6, Story 21.4): the Mentor turned it off, or J8 or J10 applied.
 * {@code matching}'s replay-safe listener lapses every open Crew Application of the Job at once, telling each applicant
 * why. Published in the transaction that closed it, under the Job row lock, so no Crew Application commits after it.
 * @param closedAt when it closed: the listener lapses only the applications sent by then, so one sent after a quick
 * reopening, or a replayed publication, ends nothing that came later
 */
public record CrewRecruitmentClosed(UUID eventId, UUID jobId, ClosedBy closedBy, Instant closedAt) implements DomainEvent {

	public CrewRecruitmentClosed {
		Objects.requireNonNull(eventId, "eventId");
		Objects.requireNonNull(jobId, "jobId");
		Objects.requireNonNull(closedBy, "closedBy");
		Objects.requireNonNull(closedAt, "closedAt");
	}

}
