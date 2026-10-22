package vn.vibefix.repo.internal;

import java.util.List;

import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import vn.vibefix.github.GitHubApps.App;
import vn.vibefix.github.GitHubWebhookReceived;
import vn.vibefix.job.ClosureProposalChanged;
import vn.vibefix.job.JobBusy;
import vn.vibefix.job.JobTransitioned;
import vn.vibefix.shared.ProcessedEvents;

/**
 * What moves a Final Merge authorization (FR-37, FR-67, AD-10): the first closure proposal opens it and
 * a declined one ends it while the Job is still Active; J8 sets its deadline and J10 ends it. The Merge
 * App's {@code installation} and {@code installation_repositories} events, whatever their installation,
 * are re-read with the App's JWT: {@code created}, {@code unsuspend}, and {@code added} bind;
 * {@code deleted}, {@code suspend}, and {@code removed} return the bound authorizations to
 * {@code REQUESTED}.
 */
@Component
class FinalMergeListeners {

	private final FinalMergeAuthorizations authorizations;

	private final MergeAppBindings bindings;

	private final FinalMerges merges;

	private final JobBusy busy;

	private final ProcessedEvents processedEvents;

	FinalMergeListeners(FinalMergeAuthorizations authorizations, MergeAppBindings bindings, FinalMerges merges, JobBusy busy,
			ProcessedEvents processedEvents) {
		this.authorizations = authorizations;
		this.bindings = bindings;
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
			// Story 8.8: an authorization already bound before Closing is checked now.
			this.merges.prepare(event.jobId());
		}
		else {
			this.authorizations.endOf(event.jobId(), "CANCELLED");
		}
	}

	@ApplicationModuleListener
	void onWebhook(GitHubWebhookReceived event) {
		boolean installation = "installation".equals(event.event()) || "installation_repositories".equals(event.event());
		if (event.app() != App.MERGE || !installation || event.installationId() == null
				|| !this.processedEvents.markProcessed("repo.FinalMergeListeners.onWebhook", event.eventId())) {
			return;
		}
		long id = event.installationId();
		String action = event.action() == null ? "" : event.action();
		switch (event.event() + ":" + action) {
			case "installation:created", "installation:unsuspend", "installation:new_permissions_accepted",
					"installation_repositories:added" ->
				this.bindings.bindEvent(id, event.requesterId() != null ? event.requesterId() : event.senderId());
			case "installation:deleted" -> this.bindings.unbind(id, null, true);
			case "installation:suspend" -> this.bindings.unbind(id, null, false);
			case "installation_repositories:removed" -> this.bindings.unbind(id, List.copyOf(event.repositoryIds()), false);
			default -> {
			}
		}
	}

	@Component
	@ConditionalOnBooleanProperty(name = "vibefix.scheduling.enabled", matchIfMissing = true)
	static class Reconciliation {

		private final MergeAppBindings bindings;

		Reconciliation(MergeAppBindings bindings) {
			this.bindings = bindings;
		}

		@Scheduled(cron = "${vibefix.repo.merge-app-reconcile-cron:0 15 4 * * *}", zone = "Asia/Ho_Chi_Minh")
		void run() {
			this.bindings.reconcile();
		}

	}

}
