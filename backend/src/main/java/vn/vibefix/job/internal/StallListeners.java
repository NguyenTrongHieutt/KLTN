package vn.vibefix.job.internal;

import java.util.List;

import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

import vn.vibefix.job.JobTransitioned;
import vn.vibefix.shared.ProcessedEvents;

/**
 * J4, and J11 or J12 for a Self-mentored Job, start FR-40's Agreement deadline; J6 starts the Starting one.
 * Replay-safe.
 */
@Component
class StallListeners {

	private final StallDeadlines stalls;

	private final ProcessedEvents processedEvents;

	StallListeners(StallDeadlines stalls, ProcessedEvents processedEvents) {
		this.stalls = stalls;
		this.processedEvents = processedEvents;
	}

	@ApplicationModuleListener
	void onJobTransitioned(JobTransitioned event) {
		// J11 and J12 put a Self-mentored Job in Agreement as J4 puts a normal one (AD-4, B-20).
		boolean agreement = List.of("J4", "J11", "J12").contains(event.transition());
		if ((!agreement && !"J6".equals(event.transition()))
				|| !this.processedEvents.markProcessed("job.StallListeners.onJobTransitioned", event.eventId())) {
			return;
		}
		this.stalls.schedule(event.jobId(), agreement ? JobState.AGREEMENT : JobState.STARTING);
	}

}
