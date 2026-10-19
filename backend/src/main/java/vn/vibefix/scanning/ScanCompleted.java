package vn.vibefix.scanning;

import java.util.UUID;

import vn.vibefix.shared.DomainEvent;

/**
 * A scan run read the whole range and recorded its Findings (AD-18, AD-9), published in the
 * transaction that stores them.
 * @param clean whether the run found nothing at all
 * @param personalDataFound whether it found personal data; a clean-repository candidate with any is
 * refused, and none of its Findings is stored
 */
public record ScanCompleted(UUID eventId, UUID scanRunId, UUID productId, boolean clean, boolean personalDataFound)
		implements DomainEvent {

}
