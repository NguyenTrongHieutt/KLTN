package vn.vibefix.shared;

import java.util.UUID;

/**
 * A domain event (AD-9): an immutable past-tense record in its publisher's root package, published
 * through Spring Modulith's JDBC registry and consumed with {@code @ApplicationModuleListener}.
 * Its {@link #eventId()} lets a listener recognize a replay ({@link ProcessedEvents}).
 */
public interface DomainEvent {

	UUID eventId();

}
