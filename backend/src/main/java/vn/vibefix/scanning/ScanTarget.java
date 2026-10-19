package vn.vibefix.scanning;

import java.util.Objects;
import java.util.UUID;

/**
 * What a scan reads and whom it reports to.
 * @param productName shown in the notifications, as the Product was named when the scan was requested
 * @param repositoryFullName {@code owner/name} of the public repository
 */
public record ScanTarget(UUID productId, UUID customerId, String productName, String repositoryFullName,
		long repositoryGithubId) {

	public ScanTarget {
		Objects.requireNonNull(productId, "productId");
		Objects.requireNonNull(customerId, "customerId");
		Objects.requireNonNull(productName, "productName");
		Objects.requireNonNull(repositoryFullName, "repositoryFullName");
	}

}
