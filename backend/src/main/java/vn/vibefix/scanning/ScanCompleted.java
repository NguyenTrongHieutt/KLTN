package vn.vibefix.scanning;

import java.util.List;
import java.util.UUID;

import vn.vibefix.shared.DomainEvent;

/**
 * A scan run read the whole range and recorded its Findings (AD-18, AD-9), published in the
 * transaction that stores them.
 * @param clean whether the run found nothing at all
 * @param personalDataFound whether it found personal data; a clean-repository candidate with any is
 * refused, and none of its Findings is stored
 * @param purpose why the run ran
 * @param openedFindingIds the Findings this run opened, for the module that pushes their resolvers down
 */
public record ScanCompleted(UUID eventId, UUID scanRunId, UUID productId, boolean clean, boolean personalDataFound,
		ScanPurpose purpose, List<UUID> openedFindingIds) implements DomainEvent {

	public ScanCompleted {
		openedFindingIds = List.copyOf(openedFindingIds);
	}

}
