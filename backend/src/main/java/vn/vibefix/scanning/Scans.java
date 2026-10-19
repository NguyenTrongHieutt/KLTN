package vn.vibefix.scanning;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
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

	/** Every Finding of the Product: open ones first, then the closed ones, most recently closed first. */
	List<Finding> findings(UUID productId);

	/** The Product's scan runs, newest first, without clean-repository candidates it did not adopt. */
	List<ScanSummary> history(UUID productId);

	/** How many runs of {@code purposes} the Product requested since {@code since}, for daily limits. */
	int requestedSince(UUID productId, Instant since, Set<ScanPurpose> purposes);

	/** How many Findings of each of {@code productIds} are open; a Product without any is left out. */
	Map<UUID, Integer> openFindingCounts(Collection<UUID> productIds);

	/** One of the Product's Findings, open or resolved. */
	Optional<Finding> finding(UUID productId, UUID findingId);

	/** Whether {@code accountId} is one of the Finding's resolvers. */
	boolean mayResolve(UUID findingId, UUID accountId);

	/**
	 * A resolver confirms the credential was rotated (FR-8, CUS-5 "Tôi đã đổi khóa"): the Finding is
	 * resolved, the Audit Log records who and when, and the resolve to-do closes, in one transaction.
	 * @throws vn.vibefix.shared.ApiException {@code NOT_FOUND} for another Product's Finding or a caller
	 * who is not a resolver; {@code CONFLICT FINDING_CLOSED} once resolved; {@code GUARD_FAILED
	 * NOT_A_CREDENTIAL} for personal data
	 */
	Finding confirmRotation(UUID productId, UUID findingId, UUID accountId);

	/**
	 * A resolver marks the Finding as not real (FR-8, FR-65, CUS-5): the reason, the resolver, and the
	 * time are kept, the Audit Log records it, and the resolve to-do closes.
	 * @throws vn.vibefix.shared.ApiException {@code NOT_FOUND} for another Product's Finding or a caller
	 * who is not a resolver; {@code CONFLICT FINDING_CLOSED} once closed
	 */
	Finding markFalsePositive(UUID productId, UUID findingId, UUID accountId, String reason);

	/** A scan run, for a requester following its own. */
	Optional<ScanSummary> run(UUID scanRunId);

	/**
	 * The Product switched to the clean repository {@code scanRunId} scanned (FR-65): every open
	 * personal-data Finding is resolved as {@code CLEAN_REPOSITORY}, each audited with
	 * {@code accountId} and its to-do closed, and the run becomes the Product's latest scan. Runs in
	 * the caller's transaction.
	 */
	void resolveByCleanRepository(UUID productId, UUID accountId, UUID scanRunId);

	/**
	 * Replaces the Finding's resolvers in the caller's transaction (AD-18, AD-3): Epic 7 pushes down
	 * the Mentor, the Crew, or the Customer for push and handover Findings.
	 * @throws org.springframework.transaction.IllegalTransactionStateException without a transaction
	 */
	void setResolvers(UUID findingId, Set<UUID> accountIds);

}
