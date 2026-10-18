package vn.vibefix.shared;

import java.util.List;
import java.util.UUID;

/**
 * Runs exports, erasures, and Product deletions through every {@link PersonalDataParticipant}
 * (AD-24). {@code identity} owns the export and deletion requests and calls this to carry them out.
 */
public interface PersonalData {

	/** Every participant's section for {@code accountId}, each holding only what it may see. */
	List<PersonalDataSection> export(UUID accountId);

	/**
	 * Erases {@code accountId} through every participant in one transaction, but only when no
	 * participant reports a blocker; otherwise erases nothing and returns the blockers. The caller
	 * runs it in its own transaction after locking the account row ({@code FOR UPDATE}), so that no
	 * new Job, Crew row, or Dispute can create a blocker between the check and the erasure.
	 */
	ErasureOutcome erase(UUID accountId);

	/** Deletes a Product's data through every participant. */
	void deleteProduct(UUID productId);

}
