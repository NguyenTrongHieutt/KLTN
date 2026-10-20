package vn.vibefix.job.internal;

import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

import vn.vibefix.job.JobTransitioned;
import vn.vibefix.shared.ProcessedEvents;

/** J4 and J6 start FR-40's Agreement and Starting deadlines. Replay-safe. */
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
		boolean agreement = "J4".equals(event.transition());
		if ((!agreement && !"J6".equals(event.transition()))
				|| !this.processedEvents.markProcessed("job.StallListeners.onJobTransitioned", event.eventId())) {
			return;
		}
		this.stalls.schedule(event.jobId(), agreement ? JobState.AGREEMENT : JobState.STARTING);
	}

}
