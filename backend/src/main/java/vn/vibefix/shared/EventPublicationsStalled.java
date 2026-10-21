package vn.vibefix.shared;

import java.util.UUID;

/**
 * Event publications stopped after their last attempt (AD-9): they wait in ADM-7's queue, and {@code admin}
 * alerts every Administrator ({@code operations.event_publication_stalled}).
 * @param count how many were newly marked stalled
 */
public record EventPublicationsStalled(UUID eventId, int count) implements DomainEvent {
}
