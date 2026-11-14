package vn.vibefix.repo.internal;

import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

import vn.vibefix.job.ClosureProposalChanged;
import vn.vibefix.job.JobBusy;
import vn.vibefix.job.JobTransitioned;
import vn.vibefix.shared.ProcessedEvents;

/**
 * What moves a Job's closing choice (FR-37, FR-38, FR-67, D10): the first closure proposal opens its row and a
 * declined one ends it while the Job is still Active; J8 sets the FR-37 deadline, schedules its reminder and starts
 * the Final Merge check, and J10 ends the row {@code CANCELLED}.
 * <p>
 * Since Story 23.10a {@code CANCELLED} ends the Final Merge alone. A Job Cancelled at J10 with accepted work keeps
 * the self-download path -- "Tôi đã tự tải / clone từ GitHub" and its code receipt -- until its repositories are
 * deleted ({@link CodeReceipts}), with no Final Merge card and no second deadline.
 * <p>
 * Story 23.9a retired the Merge App (R1), so no {@code installation} or {@code installation_repositories} webhook
 * and no daily reconciliation is listened for any more: there is no app to install at the end, and Story 23.9b took
 * the App and those deliveries away altogether.
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
