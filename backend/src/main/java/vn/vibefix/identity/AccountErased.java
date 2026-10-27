package vn.vibefix.identity;

import java.util.UUID;

import vn.vibefix.shared.DomainEvent;

/**
 * An Administrator confirmed an account's deletion and every participant erased it (NFR-11, AD-24): for example
 * {@code product} deletes an erased Customer's remaining Products, and with them their platform repositories
 * (FR-21). Published by {@link AccountAdministration#erase} (Story 16.2). It carries no role: a person holds roles
 * in Jobs, never on the account (Story 20.3).
 */
public record AccountErased(UUID eventId, UUID accountId) implements DomainEvent {
}
