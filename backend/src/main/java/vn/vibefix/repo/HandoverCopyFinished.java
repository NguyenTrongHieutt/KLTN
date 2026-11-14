package vn.vibefix.repo;

import java.util.UUID;

import org.jspecify.annotations.Nullable;

import vn.vibefix.repo.HandoverCopies.Refusal;
import vn.vibefix.shared.DomainEvent;

/**
 * A handover copy on the runner ended (FR-32, AD-11, AD-46, Story 23.8a): the Handover Commit is on
 * {@code handover/{submissionId}} of the Intermediary with the System's pull request open, or nothing was
 * copied and {@code refusal} says why. {@code delivery} then records the handover from its pending submission,
 * or ends the submission with that reason; {@link HandoverCopies#result} carries the pull request and the full
 * list of changed paths.
 */
public record HandoverCopyFinished(UUID eventId, UUID jobId, UUID submissionId, boolean copied,
		@Nullable Refusal refusal) implements DomainEvent {

}
