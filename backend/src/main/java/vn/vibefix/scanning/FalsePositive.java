package vn.vibefix.scanning;

import java.time.Instant;
import java.util.UUID;

import org.jspecify.annotations.Nullable;

/**
 * A Finding its resolver marked as a false positive (FR-8, FR-64), with the reason; ADM-7 lists it and
 * may reopen it.
 */
public record FalsePositive(UUID findingId, UUID productId, String productName, String ruleType, String filePath,
		String reason, @Nullable UUID markedBy, Instant markedAt) {
}
