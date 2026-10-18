package eventfixtures;

import java.util.UUID;

import vn.vibefix.shared.DomainEvent;

/** An event that exists only for the event registry tests. */
public record ProbeEvent(UUID eventId, String label) implements DomainEvent {
}
