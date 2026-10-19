package vn.vibefix.scanning;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** Requests Secret Scans and reads their results (AD-18). */
public interface Scans {

	/**
	 * Records a scan of {@code target} in the caller's transaction, with the External Operation that
	 * runs it, so the scan is requested exactly when the change that causes it commits.
	 * @return the scan run's ID
	 * @throws org.springframework.transaction.IllegalTransactionStateException without a transaction
	 */
	UUID requestScan(ScanTarget target, ShaRange shaRange, ScanPurpose purpose);

	/** The Product's latest scan run. */
	Optional<ScanSummary> latest(UUID productId);

	/** The latest scan run of each of {@code productIds} that has one. */
	Map<UUID, ScanSummary> latest(Collection<UUID> productIds);

	/** The Product's open Findings, newest commit first. */
	List<Finding> openFindings(UUID productId);

	/** How many Findings of each of {@code productIds} are open; a Product without any is left out. */
	Map<UUID, Integer> openFindingCounts(Collection<UUID> productIds);

}
