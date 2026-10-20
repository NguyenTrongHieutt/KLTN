package vn.vibefix.reputation;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

import org.jspecify.annotations.Nullable;

/**
 * One published Portfolio Entry (FR-50, FR-51): what a Job closed with Accepted work says about a Mentor
 * or a Crew member, frozen at J9 from the platform's records, never written by hand. It links to no
 * repository and holds no Page content.
 * @param role {@code MENTOR} or {@code CREW}
 * @param jobTypes the Job Types' codes
 * @param skills the frozen Skills by their current names (AD-38)
 * @param milestones how many Milestones were accepted
 * @param workItemsApproved a Crew member's Work Items completed through an approval
 * @param milestonesHandedOver the Mentor's Milestones handed over
 * @param reviewsWritten the Mentor's reviews (approvals and returns)
 * @param rating the Customer's Rating of the Mentor, or the Mentor's of the Crew member, once visible
 * @param summary the summary the Customer approved
 * @param productName the Product's name, when the Customer allowed it
 */
public record PortfolioEntry(String title, String role, List<String> jobTypes, List<String> skills, Instant closedAt,
		int milestones, @Nullable Integer workItemsApproved, @Nullable Integer milestonesHandedOver,
		@Nullable Integer reviewsWritten, @Nullable Integer rating, @Nullable String summary, @Nullable String productName) {

	public PortfolioEntry {
		Objects.requireNonNull(title, "title");
		Objects.requireNonNull(role, "role");
		jobTypes = List.copyOf(jobTypes);
		skills = List.copyOf(skills);
		Objects.requireNonNull(closedAt, "closedAt");
	}

}
