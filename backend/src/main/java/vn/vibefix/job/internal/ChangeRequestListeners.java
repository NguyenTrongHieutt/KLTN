package vn.vibefix.job.internal;

import java.util.Set;
import java.util.UUID;

import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

import vn.vibefix.job.JobTransitioned;
import vn.vibefix.shared.ProcessedEvents;

/**
 * An open Change Request lapses (FR-36): on M5 or M8 of a Milestone it touches, or on J8 or J10.
 * Replay-safe.
 */
@Component
class ChangeRequestListeners {

	private static final Set<String> MILESTONE_ENDS = Set.of("M5", "M8");

	private static final Set<String> JOB_ENDS = Set.of("J8", "J10");

	private final ChangeRequests requests;

	private final ProcessedEvents processedEvents;

	ChangeRequestListeners(ChangeRequests requests, ProcessedEvents processedEvents) {
		this.requests = requests;
		this.processedEvents = processedEvents;
	}

	@ApplicationModuleListener
	void onJobTransitioned(JobTransitioned event) {
		boolean milestone = MILESTONE_ENDS.contains(event.transition());
		if ((!milestone && !JOB_ENDS.contains(event.transition()))
				|| !this.processedEvents.markProcessed("job.ChangeRequestListeners.onJobTransitioned", event.eventId())) {
			return;
		}
		String milestoneId = event.facts().get("milestoneId");
		if (milestone && milestoneId == null) {
			return;
		}
		this.requests.lapse(event.jobId(), milestone ? UUID.fromString(milestoneId) : null);
	}

}
