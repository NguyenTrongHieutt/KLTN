package vn.vibefix.identity;

import java.util.UUID;

import vn.vibefix.shared.DomainEvent;
import vn.vibefix.shared.Role;

/**
 * An Administrator confirmed an account's deletion and every participant erased it (NFR-11, AD-24): for example
 * {@code product} deletes an erased Customer's remaining Products, and with them their platform repositories
 * (FR-21). Published by {@link AccountAdministration#erase} (Story 16.2).
 */
public record AccountErased(UUID eventId, UUID accountId, Role role) implements DomainEvent {
}
