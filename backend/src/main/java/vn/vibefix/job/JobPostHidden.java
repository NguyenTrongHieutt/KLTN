package vn.vibefix.job;

import java.util.UUID;

import vn.vibefix.shared.DomainEvent;

/**
 * An Open Job Post was hidden (FR-60), by an Administrator or with its hidden Product: {@code matching}
 * lapses its Invitations to Apply and tells its applicants (FR-14).
 */
public record JobPostHidden(UUID eventId, UUID jobId) implements DomainEvent {
}
