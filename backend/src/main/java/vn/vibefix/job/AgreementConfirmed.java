package vn.vibefix.job;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

import vn.vibefix.shared.DomainEvent;

/**
 * The Mentor confirmed the Agreement (FR-17, AD-9): the Job stays in Agreement until Milestone 1 is
 * funded. {@code payment} opens the funding of Milestone 1 from it.
 * @param milestones the confirmed Milestones in their order
 */
public record AgreementConfirmed(UUID eventId, UUID jobId, UUID productId, UUID customerId, String title,
		int agreementVersionNo, List<ConfirmedMilestone> milestones) implements DomainEvent {

	/** One confirmed Milestone: its number from 1, its title, and its price in whole VND. */
	public record ConfirmedMilestone(UUID milestoneId, int number, String title, long priceVnd) {
	}

	public AgreementConfirmed {
		Objects.requireNonNull(eventId, "eventId");
		Objects.requireNonNull(jobId, "jobId");
		Objects.requireNonNull(customerId, "customerId");
		milestones = List.copyOf(milestones);
	}

}
