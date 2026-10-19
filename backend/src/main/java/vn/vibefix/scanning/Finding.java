package vn.vibefix.scanning;

import java.time.Instant;
import java.util.UUID;

import org.jspecify.annotations.Nullable;

/**
 * A credential, or a file of personal data, found in a Product's history (FR-8, FR-65, AD-18): the
 * rule, its plain type, the file and the first commit the scan met it in, and a masked preview (for
 * personal data, up to two masked samples). The values themselves are never kept.
 * @param matchCount for personal data, the lines holding a personal value
 */
public record Finding(UUID id, FindingCategory category, String ruleId, String type, String path, String commit,
		Instant committedAt, String maskedPreview, @Nullable Integer matchCount, FindingStatus status,
		Instant createdAt) {

}
