package vn.vibefix.timers;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

import org.jspecify.annotations.Nullable;

/**
 * One Deadline to schedule (AD-7).
 * @param kind the {@link DeadlineKind} key, for example {@code identity.verification_expiry}
 * @param subjectId the record the rule is about
 * @param generation which state of the subject it serves (the handover, proposal, Dispute, or order);
 * its handler answers {@code OBSOLETE} once the subject has moved on
 * @param jobId the Job, mandatory for a job-scoped kind
 * @param dueAt when the rule runs
 */
public record DeadlineRequest(String kind, UUID subjectId, String generation, @Nullable UUID jobId, Instant dueAt) {

	public DeadlineRequest {
		Objects.requireNonNull(kind, "kind");
		Objects.requireNonNull(subjectId, "subjectId");
		Objects.requireNonNull(generation, "generation");
		Objects.requireNonNull(dueAt, "dueAt");
	}

}
