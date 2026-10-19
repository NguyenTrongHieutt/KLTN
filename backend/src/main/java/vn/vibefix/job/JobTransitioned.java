package vn.vibefix.job;

import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import org.jspecify.annotations.Nullable;

import vn.vibefix.shared.DomainEvent;

/**
 * A Job or one of its Milestones changed state (AD-4, AD-9), published once by every transition in
 * the transaction that applied it.
 * @param transitionId the applied transition, also the cause its notifications name
 * @param transition the PRD section 4.0 entry, {@code J1} to {@code J10} or {@code M1} to {@code M8}
 * @param trigger {@code USER}, {@code AUTO}, {@code DECISION}, or {@code SYSTEM}
 * @param from the state it left
 * @param to the state it entered
 * @param version the Job's version after the transition
 * @param agreementVersionNo the confirmed Agreement version, once there is one (FR-17)
 * @param facts what its consequences depend on, for example {@code reason} of J2 ({@code WITHDRAWN} or
 * {@code LAPSED})
 */
public record JobTransitioned(UUID eventId, UUID transitionId, UUID jobId, String transition, String trigger,
		String from, String to, long version, @Nullable Integer agreementVersionNo, Map<String, String> facts)
		implements DomainEvent {

	public JobTransitioned {
		Objects.requireNonNull(eventId, "eventId");
		Objects.requireNonNull(transitionId, "transitionId");
		Objects.requireNonNull(jobId, "jobId");
		facts = Map.copyOf(facts);
	}

}
