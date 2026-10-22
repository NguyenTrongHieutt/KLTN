package vn.vibefix.shared;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * A module's part in exports, erasure, and Product deletion (AD-24). Every module holding personal
 * data implements it for its own tables, L1 modules included; {@code PersonalDataCoverageIT} fails
 * when a table with account IDs has no participant. Only {@link PersonalData} calls participants
 * (AD-3 port).
 */
public interface PersonalDataParticipant {

	/** The tables this participant answers for, as {@code schema.table}. */
	Set<String> tables();

	/**
	 * What {@code accountId} may see of its own data in this module (AD-20), never another person's
	 * data; empty when there is none.
	 */
	Optional<PersonalDataSection> export(UUID accountId);

	/**
	 * Erases or anonymizes {@code accountId}'s data. Runs only after every participant reported no
	 * {@link #erasureBlockers}, inside the deletion transaction; records the law requires are
	 * anonymized, not deleted.
	 */
	void eraseAccount(UUID accountId);

	/** Deletes what this module holds for a deleted Product. */
	void deleteProduct(UUID productId);

	/**
	 * Deletes or anonymizes the Product's Job data at the end of its retention (NFR-10, AD-24): 12 months
	 * after its last Job ended. The Product stays. Only the modules holding what NFR-10 names act (Pages,
	 * the Job Chat and the Crew Chat, Work Items with their notes and Attachments, files, ZIPs); every
	 * other module keeps its records, financial and Dispute records above all.
	 */
	default void retireJobData(UUID productId) {
	}

	/**
	 * What keeps {@code accountId} from being erased now: for example running Jobs and unreleased
	 * Crew rows ({@code job}), open Disputes and Team Issues ({@code dispute}).
	 */
	List<ErasureBlocker> erasureBlockers(UUID accountId);

}
