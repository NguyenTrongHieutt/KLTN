package vn.vibefix.job.internal;

import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

import vn.vibefix.job.CrewMemberRemoved;
import vn.vibefix.shared.ProcessedEvents;

/**
 * A member who leaves a Self-mentored Job, or is locked, no longer counts for its open cancellation proposal (AD-47
 * exception 6, R56, Story 25.7): their answer to-do closes, and when those who still count have all agreed the
 * cancellation applies then, not only at the proposal's date. Replay-safe through {@link ProcessedEvents}.
 */
@Component
class CancellationListeners {

	private final Cancellations cancellations;

	private final ProcessedEvents processedEvents;

	CancellationListeners(Cancellations cancellations, ProcessedEvents processedEvents) {
		this.cancellations = cancellations;
		this.processedEvents = processedEvents;
	}

	@ApplicationModuleListener
	void onCrewMemberRemoved(CrewMemberRemoved event) {
		if (!this.processedEvents.markProcessed("job.CancellationListeners.onCrewMemberRemoved", event.eventId())) {
			return;
		}
		this.cancellations.memberGone(event.jobId(), event.studentId());
	}

}
