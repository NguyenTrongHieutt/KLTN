package vn.vibefix.repo;

import java.util.UUID;

import vn.vibefix.shared.DomainEvent;

/**
 * A Code Job's Final Merge authorization ended without the merge (FR-37, FR-38, AD-10): its closure
 * proposal ended without J8 ({@code PROPOSAL_ENDED}), the Job was cancelled ({@code CANCELLED}), or the
 * FR-37 deadline passed with nothing bound ({@code DEADLINE}); the ZIP Fallback follows from Closing.
 */
public record FinalMergeAuthorizationEnded(UUID eventId, UUID jobId, UUID authorizationId, String reason)
		implements DomainEvent {

}
