package vn.vibefix.repo;

import java.util.UUID;

import vn.vibefix.shared.DomainEvent;

/**
 * A Code Job's Final Merge ended without the code reaching the Customer's project (FR-37, FR-38): its closure
 * proposal ended without J8 ({@code PROPOSAL_ENDED}), the Job was cancelled ({@code CANCELLED}), the FR-37 deadline
 * passed with nothing confirmed ({@code DEADLINE}), the Customer chose the other way ({@code DECLINED}), or the run
 * could not copy the code ({@code ZIP_FALLBACK}, with the Final Merge's own named reason on its row). The closure
 * fallback follows from Closing (Story 8.9, which Story 23.10 replaces).
 */
public record FinalMergeAuthorizationEnded(UUID eventId, UUID jobId, UUID authorizationId, String reason)
		implements DomainEvent {

}
