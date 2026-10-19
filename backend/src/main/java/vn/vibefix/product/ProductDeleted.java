package vn.vibefix.product;

import java.util.UUID;

import vn.vibefix.shared.DomainEvent;

/**
 * A Customer deleted a Product (FR-7, AD-24), published in the transaction in which every
 * participant deleted its data for it and its Source Repository's ID was released.
 */
public record ProductDeleted(UUID eventId, UUID productId, UUID customerId) implements DomainEvent {

}
