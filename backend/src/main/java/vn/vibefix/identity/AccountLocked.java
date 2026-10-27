package vn.vibefix.identity;

import java.util.UUID;

import vn.vibefix.shared.DomainEvent;

/**
 * An Administrator locked an account (AD-19): each module ends what the person may no longer do, on every
 * relation the account holds, Job by Job. A consequence acts only on the relations the account holds, so it
 * changes nothing for one it does not hold; for example {@code team} releases a locked Team member, or closes a
 * locked Mentor's Team (AD-37). Published by {@link AccountAdministration#lock} (Story 10.1). It carries no role:
 * a person holds roles in Jobs, never on the account (Story 20.3).
 */
public record AccountLocked(UUID eventId, UUID accountId) implements DomainEvent {
}
