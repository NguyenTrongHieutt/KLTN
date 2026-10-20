package vn.vibefix.work.internal;

import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

import vn.vibefix.job.AgreementConfirmed;
import vn.vibefix.job.JobTransitioned;
import vn.vibefix.shared.ProcessedEvents;

/**
 * J7 creates the Job's Board and seeds one Story per Acceptance Criterion (FR-22, AD-5); a later
 * confirmed Agreement version seeds the criteria the Board does not have yet (AD-29). Each is
 * replay-safe through {@link ProcessedEvents}, and the criterion's stable ID keys every seeded Story.
 */
@Component
class WorkListeners {

	private final Boards boards;

	private final ProcessedEvents processedEvents;

	WorkListeners(Boards boards, ProcessedEvents processedEvents) {
		this.boards = boards;
		this.processedEvents = processedEvents;
	}

	@ApplicationModuleListener
	void onJobTransitioned(JobTransitioned event) {
		if ("J7".equals(event.transition())
				&& this.processedEvents.markProcessed("work.WorkListeners.onJobTransitioned", event.eventId())) {
			this.boards.seed(event.jobId(), true);
		}
	}

	@ApplicationModuleListener
	void onAgreementConfirmed(AgreementConfirmed event) {
		if (event.agreementVersionNo() > 1
				&& this.processedEvents.markProcessed("work.WorkListeners.onAgreementConfirmed", event.eventId())) {
			this.boards.seed(event.jobId(), false);
		}
	}

}
