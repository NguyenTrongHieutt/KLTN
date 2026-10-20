package vn.vibefix.dispute.internal;

import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

import vn.vibefix.job.JobNeedsDecision;
import vn.vibefix.shared.ProcessedEvents;

/**
 * {@code JobNeedsDecision} (FR-59, AD-19): one open Dispute on the Job, by the System. Replay-safe: the
 * event is marked, and an open Dispute is never opened twice.
 */
@Component
class DisputeListeners {

	private final Disputes disputes;

	private final ProcessedEvents processedEvents;

	DisputeListeners(Disputes disputes, ProcessedEvents processedEvents) {
		this.disputes = disputes;
		this.processedEvents = processedEvents;
	}

	@ApplicationModuleListener
	void onJobNeedsDecision(JobNeedsDecision event) {
		if (!this.processedEvents.markProcessed("dispute.DisputeListeners.onJobNeedsDecision", event.eventId())) {
			return;
		}
		this.disputes.openForSystem(event.jobId());
	}

}
