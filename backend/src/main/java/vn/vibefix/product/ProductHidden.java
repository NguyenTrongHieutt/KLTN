package vn.vibefix.product;

import java.util.UUID;

import vn.vibefix.shared.DomainEvent;

/** An Administrator hid a Product (FR-60): {@code job} hides its Open Job Posts. */
public record ProductHidden(UUID eventId, UUID productId) implements DomainEvent {
}
