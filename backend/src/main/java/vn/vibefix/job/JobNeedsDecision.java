package vn.vibefix.job;

import java.util.UUID;

import vn.vibefix.shared.DomainEvent;

/**
 * A running Job can no longer go on by itself (FR-59, AD-19): a lock took its Mentor or its Customer.
 * {@code dispute} opens one Dispute on it, which freezes it. A Crew left with no member opens none: the Mentor
 * carries on (FR-82, Story 20.4).
 * @param cause {@code MENTOR_LOCKED} or {@code CUSTOMER_LOCKED}
 */
public record JobNeedsDecision(UUID eventId, UUID jobId, String cause) implements DomainEvent {
}
