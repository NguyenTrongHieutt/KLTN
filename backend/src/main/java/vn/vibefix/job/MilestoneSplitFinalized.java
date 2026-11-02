package vn.vibefix.job;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import vn.vibefix.shared.DomainEvent;

/**
 * A Milestone Split became final (AD-9, AD-15, AD-29): {@code payment}'s replay-safe listener alone turns the
 * Milestone's pool into RELEASE entries, by these percentages. {@code chat}, {@code work}, {@code delivery} and
 * {@code job} finish what a Product deletion or the retention run left waiting once the Job has no open split (AD-24).
 * Published in the transaction that made it final, under the Job row lock.
 * @param milestoneSplitId the split, which keys the RELEASE entries
 * @param milestoneNumber the Milestone's number, for the payees' notice
 * @param jobTitle the Job's title, for the payees' notice: the Job's row may be gone by then
 * @param mentorId who receives the remainder of the whole-VND split
 * @param finalVersionNo the final version, recorded on each RELEASE
 * @param percentages each party's whole percentage in that version, a party at 0 included
 * @param finality how it became final
 */
public record MilestoneSplitFinalized(UUID eventId, UUID milestoneSplitId, UUID jobId, UUID productId, UUID milestoneId,
		int milestoneNumber, String jobTitle, UUID mentorId, int finalVersionNo, Map<UUID, Integer> percentages,
		Finality finality) implements DomainEvent {

	/** How a split became final: a confirmation by every party, its Deadline, a decision, or the Mentor alone. */
	public enum Finality {
		CONFIRMED, DEADLINE, DECISION, MENTOR_ONLY
	}

	public MilestoneSplitFinalized {
		Objects.requireNonNull(eventId, "eventId");
		Objects.requireNonNull(milestoneSplitId, "milestoneSplitId");
		Objects.requireNonNull(jobId, "jobId");
		Objects.requireNonNull(productId, "productId");
		Objects.requireNonNull(milestoneId, "milestoneId");
		Objects.requireNonNull(jobTitle, "jobTitle");
		Objects.requireNonNull(mentorId, "mentorId");
		Objects.requireNonNull(finality, "finality");
		percentages = new LinkedHashMap<>(percentages);
	}

}
