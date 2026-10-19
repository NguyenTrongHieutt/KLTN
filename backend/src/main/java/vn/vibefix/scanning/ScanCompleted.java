package vn.vibefix.scanning;

import java.util.UUID;

import vn.vibefix.shared.DomainEvent;

/**
 * A scan run read the whole range and recorded its Findings (AD-18, AD-9), published in the
 * transaction that stores them.
 * @param clean whether the run found no credential at all
 */
public record ScanCompleted(UUID eventId, UUID scanRunId, UUID productId, boolean clean) implements DomainEvent {

}
