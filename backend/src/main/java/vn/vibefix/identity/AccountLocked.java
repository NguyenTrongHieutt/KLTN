package vn.vibefix.identity;

import java.util.UUID;

import vn.vibefix.shared.DomainEvent;
import vn.vibefix.shared.Role;

/**
 * An Administrator locked an account (AD-19): each module ends what the person may no longer do,
 * for example {@code team} releases a locked Student, or closes a locked Mentor's Team (AD-37).
 * Published by {@link AccountAdministration#lock} (Story 10.1).
 */
public record AccountLocked(UUID eventId, UUID accountId, Role role) implements DomainEvent {
}
