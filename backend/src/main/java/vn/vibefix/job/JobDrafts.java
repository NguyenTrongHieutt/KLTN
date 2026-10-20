package vn.vibefix.job;

import java.util.List;
import java.util.UUID;

import org.jspecify.annotations.Nullable;

/**
 * A Draft Job Post made for the Customer from a follow-up (FR-11): the same Draft CUS-6 saves, which
 * only the Customer's J1 publishes (FR-9).
 */
public interface JobDrafts {

	/**
	 * @param jobTypes Job Type codes
	 * @return the Draft's Job ID
	 * @throws vn.vibefix.shared.ApiException as CUS-6's save: {@code NOT_FOUND} for a Product that is not the
	 * Customer's, {@code VALIDATION ASSESSMENT_ALONE}, {@code GUARD_FAILED OPEN_FINDINGS}, Skill refusals
	 */
	UUID createDraft(UUID customerId, UUID productId, String title, @Nullable String problem, @Nullable String expected,
			List<String> jobTypes, List<UUID> skillIds);

}
