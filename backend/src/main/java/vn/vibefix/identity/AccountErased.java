package vn.vibefix.identity;

import java.util.UUID;

import org.jspecify.annotations.Nullable;

import vn.vibefix.shared.DomainEvent;
import vn.vibefix.shared.Role;

/**
 * An Administrator confirmed an account's deletion and every participant erased it (NFR-11, AD-24): for example
 * {@code product} deletes an erased Customer's remaining Products, and with them their platform repositories
 * (FR-21). Published by {@link AccountAdministration#erase} (Story 16.2).
 * @param role the account's permanent role; {@code null} for a Google account, which has none
 */
public record AccountErased(UUID eventId, UUID accountId, @Nullable Role role) implements DomainEvent {
}
