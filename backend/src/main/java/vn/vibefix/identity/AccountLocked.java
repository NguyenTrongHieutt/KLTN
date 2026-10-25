package vn.vibefix.identity;

import java.util.UUID;

import org.jspecify.annotations.Nullable;

import vn.vibefix.shared.DomainEvent;
import vn.vibefix.shared.Role;

/**
 * An Administrator locked an account (AD-19): each module ends what the person may no longer do,
 * for example {@code team} releases a locked Student, or closes a locked Mentor's Team (AD-37).
 * Published by {@link AccountAdministration#lock} (Story 10.1).
 * @param role the account's permanent role; {@code null} for a Google account, which has none until
 * Story 20.4 extends the lock to every relation
 */
public record AccountLocked(UUID eventId, UUID accountId, @Nullable Role role) implements DomainEvent {
}
