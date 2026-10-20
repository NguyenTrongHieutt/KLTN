package vn.vibefix.job;

import java.util.UUID;

import vn.vibefix.shared.DomainEvent;

/**
 * A running Job can no longer go on by itself (FR-59, AD-19): a lock took its Mentor, its Customer, or
 * every Crew member who could work. {@code dispute} opens one Dispute on it, which freezes it.
 * @param cause {@code CREW_LOCKED}, {@code MENTOR_LOCKED}, or {@code CUSTOMER_LOCKED}
 */
public record JobNeedsDecision(UUID eventId, UUID jobId, String cause) implements DomainEvent {
}
