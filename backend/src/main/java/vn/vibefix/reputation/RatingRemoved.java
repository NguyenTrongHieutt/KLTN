package vn.vibefix.reputation;

import java.util.Objects;
import java.util.UUID;

import vn.vibefix.shared.DomainEvent;

/**
 * A Rating stopped counting (FR-49, FR-60, FR-79): an Administrator hid it, or a Team Issue removed it. No
 * screen shows it and no summary counts it from then on.
 */
public record RatingRemoved(UUID eventId, UUID ratingId) implements DomainEvent {

	public RatingRemoved {
		Objects.requireNonNull(eventId, "eventId");
		Objects.requireNonNull(ratingId, "ratingId");
	}

}
