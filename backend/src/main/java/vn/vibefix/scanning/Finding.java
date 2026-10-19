package vn.vibefix.scanning;

import java.time.Instant;
import java.util.UUID;

/**
 * A credential found in a Product's history (FR-8, AD-18): the rule, its plain type, the file and the
 * first commit the scan met it in, and a masked preview. The credential itself is never kept.
 */
public record Finding(UUID id, String ruleId, String type, String path, String commit, Instant committedAt,
		String maskedPreview, FindingStatus status, Instant createdAt) {

}
