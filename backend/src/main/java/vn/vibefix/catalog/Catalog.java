package vn.vibefix.catalog;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

/** Reads the Skill catalog and checks a holder's choice of Skills against it (AD-38). */
public interface Catalog {

	/** The active Categories, each with its active Skills, in display order. */
	List<SkillCategory> active();

	/** The Skills with these IDs, inactive ones included, in the order of {@code ids}; unknown IDs are left out. */
	List<Skill> byIds(Collection<UUID> ids);

	/**
	 * Checks Skills a holder is adding: each must exist and be active.
	 * @throws vn.vibefix.shared.ApiException {@code VALIDATION} with reason {@code SKILL_INACTIVE}
	 */
	void requireActive(Collection<UUID> added);

	/**
	 * Checks how many Skills a holder keeps: 20 on a Student or Mentor profile, 10 on a Job Post.
	 * @throws vn.vibefix.shared.ApiException {@code VALIDATION} with reason {@code SKILL_LIMIT} and the
	 * detail member {@code limit}
	 */
	void requireAtMost(int count, int max);

}
