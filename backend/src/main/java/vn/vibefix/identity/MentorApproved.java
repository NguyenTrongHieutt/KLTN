package vn.vibefix.identity;

import java.util.UUID;

import vn.vibefix.shared.DomainEvent;

/**
 * An Administrator approved a Mentor (FR-74): {@code team} creates the Mentor's Team (AD-37), and the
 * Mentor's new-work commands open ({@link MentorGate#requireApprovedMentor}).
 */
public record MentorApproved(UUID eventId, UUID accountId) implements DomainEvent {
}
