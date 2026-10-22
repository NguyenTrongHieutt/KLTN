package vn.vibefix.job;

import java.util.List;
import java.util.UUID;

/**
 * The External Access items of a Job's confirmed Agreement (FR-16, FR-66), for {@code repo}'s Revocation
 * Checklist when the Job ends: what the Customer granted outside GitHub, and the identifiers the Mentor
 * and each Crew member entered, so the Customer knows what to remove.
 */
public interface ExternalAccessItems {

	/** One identifier beside its owner's GitHub username. */
	record Identifier(String githubLogin, String identifier) {
	}

	/**
	 * One item.
	 * @param environment {@code NON_PRODUCTION} or {@code PRODUCTION}
	 * @param accessLevel {@code READ}, {@code WRITE}, or {@code ADMIN}
	 */
	record Item(UUID id, String system, String purpose, String environment, String accessLevel,
			List<Identifier> identifiers) {

		public Item {
			identifiers = List.copyOf(identifiers);
		}

	}

	/** The items, in the Agreement's order; none before the Agreement is confirmed. */
	List<Item> items(UUID jobId);

}
