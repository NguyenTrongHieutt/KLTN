package vn.vibefix.job;

import java.util.UUID;

import vn.vibefix.shared.DomainEvent;

/**
 * A closure proposal was made or declined (FR-37, FR-67): {@code repo} opens a Code Job's Final Merge
 * authorization at the first proposal and ends it when its proposal ends without J8 (AD-10).
 * @param automatic the System's proposal after the last acceptance (FR-67)
 */
public record ClosureProposalChanged(UUID eventId, UUID jobId, UUID proposalId, Status status, boolean automatic)
		implements DomainEvent {

	/** Where the proposal went. */
	public enum Status {

		OPEN, DECLINED

	}

}
