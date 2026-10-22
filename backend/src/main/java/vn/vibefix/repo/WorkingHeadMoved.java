package vn.vibefix.repo;

import java.util.Objects;
import java.util.UUID;

import vn.vibefix.shared.DomainEvent;

/**
 * {@code repo} read back a new head of a Job's Working Repository default branch (FR-32, AD-12), after a
 * push hint or the 10-minute sweep: {@code delivery} voids a handover whose Handover Commit it no longer
 * is (M3).
 */
public record WorkingHeadMoved(UUID eventId, UUID jobId, String branch, String sha) implements DomainEvent {

	public WorkingHeadMoved {
		Objects.requireNonNull(eventId, "eventId");
		Objects.requireNonNull(jobId, "jobId");
		Objects.requireNonNull(branch, "branch");
		Objects.requireNonNull(sha, "sha");
	}

}
