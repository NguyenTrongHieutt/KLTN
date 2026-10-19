package vn.vibefix.reputation;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * One Portfolio Entry (FR-50): what a Job closed with Accepted work says about a Mentor or a Crew
 * member, frozen at J9 from the platform's records, never written by hand.
 * @param jobTypes the Job Types' codes
 * @param milestones how many Milestones were accepted
 */
public record PortfolioEntry(String title, List<String> jobTypes, Instant closedAt, int milestones) {

	public PortfolioEntry {
		Objects.requireNonNull(title, "title");
		jobTypes = List.copyOf(jobTypes);
		Objects.requireNonNull(closedAt, "closedAt");
	}

}
