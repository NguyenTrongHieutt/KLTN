package vn.vibefix.repo.internal;

import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

import vn.vibefix.job.ClosureProposalChanged;
import vn.vibefix.job.JobBusy;
import vn.vibefix.job.JobTransitioned;
import vn.vibefix.shared.ProcessedEvents;

/**
 * What moves a Job's Final Merge (FR-37, FR-67): the first closure proposal opens its row and a declined one ends it
 * while the Job is still Active; J8 sets the FR-37 deadline and starts the check, and J10 ends it. Story 23.9a
 * retired the Merge App (R1), so no {@code installation} or {@code installation_repositories} webhook and no daily
 * reconciliation is listened for any more: there is no app to install at the end.
 */
@Component
class FinalMergeListeners {

	private final FinalMergeAuthorizations authorizations;

	private final FinalMerges merges;

	private final JobBusy busy;

	private final ProcessedEvents processedEvents;

	FinalMergeListeners(FinalMergeAuthorizations authorizations, FinalMerges merges, JobBusy busy,
			ProcessedEvents processedEvents) {
		this.authorizations = authorizations;
		this.merges = merges;
		this.busy = busy;
		this.processedEvents = processedEvents;
	}

	@ApplicationModuleListener
	void onClosureProposalChanged(ClosureProposalChanged event) {
		if (!this.processedEvents.markProcessed("repo.FinalMergeListeners.onClosureProposalChanged", event.eventId())) {
			return;
		}
		if (event.status() == ClosureProposalChanged.Status.OPEN) {
			this.authorizations.open(event.jobId());
		}
		else if (this.busy.job(event.jobId()).filter(job -> "ACTIVE".equals(job.state())).isPresent()) {
			this.authorizations.endOf(event.jobId(), "PROPOSAL_ENDED");
		}
	}

	@ApplicationModuleListener
	void onJobTransitioned(JobTransitioned event) {
		boolean relevant = "J8".equals(event.transition()) || "J10".equals(event.transition());
		if (!relevant || !this.processedEvents.markProcessed("repo.FinalMergeListeners.onJobTransitioned", event.eventId())) {
			return;
		}
		if ("J8".equals(event.transition())) {
			this.authorizations.closing(event.jobId());
			// The code the Customer may receive is checked as soon as the Job is Closing.
			this.merges.prepare(event.jobId());
		}
		else {
			this.authorizations.endOf(event.jobId(), "CANCELLED");
		}
	}

}
