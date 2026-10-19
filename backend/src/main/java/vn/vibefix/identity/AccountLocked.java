package vn.vibefix.identity;

import java.util.UUID;

import vn.vibefix.shared.DomainEvent;
import vn.vibefix.shared.Role;

/**
 * An Administrator locked an account (AD-19): each module ends what the person may no longer do,
 * for example {@code team} releases a locked Student, or closes a locked Mentor's Team (AD-37).
 * Published by the lock story; {@code team} listens now.
 */
public record AccountLocked(UUID eventId, UUID accountId, Role role) implements DomainEvent {
}
