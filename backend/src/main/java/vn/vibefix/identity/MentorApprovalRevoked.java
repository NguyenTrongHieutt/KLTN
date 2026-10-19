package vn.vibefix.identity;

import java.util.UUID;

import vn.vibefix.shared.DomainEvent;

/**
 * An Administrator revoked a Mentor's approval (FR-59, FR-74): {@code team} closes the Mentor's Team
 * and releases its Students (AD-37). Published by the revocation story; {@code team} listens now.
 */
public record MentorApprovalRevoked(UUID eventId, UUID accountId) implements DomainEvent {
}
