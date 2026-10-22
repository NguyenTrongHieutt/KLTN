package vn.vibefix.repo.internal;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import vn.vibefix.github.GitHubException;
import vn.vibefix.github.PlatformRepositories;
import vn.vibefix.github.PlatformRepositories.PlatformRepository;
import vn.vibefix.job.JobBusy;
import vn.vibefix.job.JobBusy.BusyJob;
import vn.vibefix.operations.Lane;
import vn.vibefix.operations.OperationHandler;
import vn.vibefix.operations.OperationRun;
import vn.vibefix.operations.Outcome;
import vn.vibefix.repo.internal.JobSetups.Setup;
import vn.vibefix.repo.internal.RepositoryDeletions.Pending;
import vn.vibefix.repo.internal.ZipFallbacks.ZipFallback;
import vn.vibefix.scanning.SensitiveChanges;
import vn.vibefix.scanning.SensitiveChanges.SensitiveChange;

/**
 * {@value ZipFallbacks#BUILD} (FR-38, AD-32, NFR-10): the ZIP of the Intermediary default branch, built in
 * the sandbox as Story 7.7's final ZIP and stored once on the Setup, owned by the Customer; then the whole
 * Job's Sensitive Changes for N-17 and the resolving transaction's J9. A frozen Job waits. Any Merge App
 * installation no open authorization needs is removed afterwards. Collaborators are looked up on use.
 */
@Component
class ZipFallbackOperation implements OperationHandler {

	static final Duration FROZEN_WAIT = Duration.ofMinutes(15);

	private static final Logger log = LoggerFactory.getLogger(ZipFallbackOperation.class);

	private final ObjectProvider<ZipFallbacks> fallbacks;

	private final ObjectProvider<JobSetups> setups;

	private final ObjectProvider<RepositoryDeletions> deletions;

	private final ObjectProvider<PlatformRepositories> repositories;

	private final ObjectProvider<SensitiveChanges> sensitive;

	private final ObjectProvider<FinalMergeAuthorizations> authorizations;

	private final ObjectProvider<MergeAppBindings> bindings;

	private final ObjectProvider<JobBusy> busy;

	private final Clock clock;

	ZipFallbackOperation(ObjectProvider<ZipFallbacks> fallbacks, ObjectProvider<JobSetups> setups,
			ObjectProvider<RepositoryDeletions> deletions, ObjectProvider<PlatformRepositories> repositories,
			ObjectProvider<SensitiveChanges> sensitive, ObjectProvider<FinalMergeAuthorizations> authorizations,
			ObjectProvider<MergeAppBindings> bindings, ObjectProvider<JobBusy> busy, Clock clock) {
		this.fallbacks = fallbacks;
		this.setups = setups;
		this.deletions = deletions;
		this.repositories = repositories;
		this.sensitive = sensitive;
		this.authorizations = authorizations;
		this.bindings = bindings;
		this.busy = busy;
		this.clock = clock;
	}

	@Override
	public String type() {
		return ZipFallbacks.BUILD;
	}

	@Override
	public Lane lane() {
		return Lane.GITHUB_LONG;
	}

	@Override
	public Outcome run(OperationRun run) {
		UUID jobId = UUID.fromString(String.valueOf(run.payload().get("jobId")));
		UUID customerId = UUID.fromString(String.valueOf(run.payload().get("customerId")));
		ZipFallback fallback = this.fallbacks.getObject().find(jobId).filter(found -> "BUILDING".equals(found.status()))
			.orElse(null);
		BusyJob job = fallback == null ? null : this.busy.getObject().job(jobId).orElse(null);
		if (fallback == null || job == null || !"CLOSING".equals(job.state())) {
			return new Outcome.Obsolete("NOT_BUILDING");
		}
		if (job.frozen()) {
			return new Outcome.Deferred(this.clock.instant().plus(FROZEN_WAIT));
		}
		Setup setup = this.setups.getObject().latest(jobId).filter(found -> "DONE".equals(found.status())).orElse(null);
		PlatformRepository intermediary = setup == null ? null : setup.intermediary();
		if (setup == null || intermediary == null || setup.defaultBranch() == null || setup.approvedSha() == null) {
			return new Outcome.Aborted("NO_INTERMEDIARY");
		}
		RepositoryDeletions deletions = this.deletions.getObject();
		PlatformRepositories repositories = this.repositories.getObject();
		List<SensitiveChange> changes;
		try {
			Pending pending = deletions.find(setup.id()).orElse(null);
			if (pending == null) {
				return new Outcome.Aborted("SETUP_GONE");
			}
			if (pending.archiveFileId() == null) {
				byte[] zip = repositories.zip(intermediary, setup.defaultBranch());
				if (!deletions.archived(setup.id(), customerId, intermediary.name() + ".zip", zip)) {
					return new Outcome.Aborted("SETUP_GONE");
				}
			}
			String head = repositories.refs(intermediary).get("refs/heads/" + setup.defaultBranch());
			changes = head == null || head.equals(setup.approvedSha()) ? List.of()
					: this.sensitive.getObject().classify(repositories.compare(intermediary, setup.approvedSha(), head).files());
		}
		catch (GitHubException failed) {
			return new Outcome.Transient(failed.status() == 0 ? "GITHUB_UNAVAILABLE" : "GITHUB_HTTP_" + failed.status());
		}
		UUID archive = Objects.requireNonNull(deletions.find(setup.id()).orElseThrow().archiveFileId());
		this.fallbacks.getObject().ready(jobId, archive, changes);
		removeInstallation(fallback.authorizationId());
		return Outcome.succeeded();
	}

	/** AD-10: the installation goes unless another open authorization needs it. */
	private void removeInstallation(UUID authorizationId) {
		Long installation = this.authorizations.getObject().find(authorizationId)
			.map(FinalMergeAuthorizations.Authorization::installationId)
			.orElse(null);
		if (installation == null) {
			return;
		}
		try {
			this.bindings.getObject().read(installation)
				.ifPresent(found -> this.bindings.getObject().removeIfUnneeded(found));
		}
		catch (GitHubException kept) {
			// The daily reconciliation removes it.
			log.warn("Merge App installation {} was not removed yet: {}", installation, kept.getMessage());
		}
	}

}
