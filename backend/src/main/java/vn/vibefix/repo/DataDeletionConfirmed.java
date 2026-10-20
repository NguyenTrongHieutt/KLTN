package vn.vibefix.repo;

import java.util.Objects;
import java.util.UUID;

import vn.vibefix.shared.DomainEvent;

/**
 * A person confirmed every data-deletion item of a Job (FR-66): {@code reputation} publishes their
 * Portfolio Entry (FR-50).
 */
public record DataDeletionConfirmed(UUID eventId, UUID jobId, UUID accountId) implements DomainEvent {

	public DataDeletionConfirmed {
		Objects.requireNonNull(eventId, "eventId");
		Objects.requireNonNull(jobId, "jobId");
		Objects.requireNonNull(accountId, "accountId");
	}

}
