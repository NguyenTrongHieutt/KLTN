package vn.vibefix.identity;

import java.time.Instant;
import java.util.UUID;

import org.jspecify.annotations.Nullable;

import vn.vibefix.shared.DomainEvent;

/**
 * An Administrator locked an account (AD-19): each module ends what the person may no longer do, on every
 * relation the account holds, Job by Job, and exactly one listener carries out each consequence. A consequence
 * acts only on the relations the account holds, so it changes nothing for one it does not hold. Published by
 * {@link AccountAdministration#lock} (Story 10.1). It carries no role: a person holds roles in Jobs, never on the
 * account (Story 20.3).
 * @param lockedAt the {@code locked_at} the lock wrote: {@code job} ends each released Crew row's window there
 * (AD-41, Story 20.4), so a backlog in the event registry moves no interval; {@code null} only for an event stored
 * before Story 20.4
 */
public record AccountLocked(UUID eventId, UUID accountId, @Nullable Instant lockedAt) implements DomainEvent {
}
