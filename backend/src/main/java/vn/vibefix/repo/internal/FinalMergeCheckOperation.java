package vn.vibefix.repo.internal;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import vn.vibefix.github.GitHubApps.InstallationToken;
import vn.vibefix.github.GitHubException;
import vn.vibefix.github.MergeApp;
import vn.vibefix.github.MergeApp.TrialMerge;
import vn.vibefix.github.PlatformRepositories;
import vn.vibefix.job.JobBusy;
import vn.vibefix.job.JobBusy.BusyJob;
import vn.vibefix.operations.Lane;
import vn.vibefix.operations.OperationHandler;
import vn.vibefix.operations.OperationRun;
import vn.vibefix.operations.Outcome;
import vn.vibefix.repo.internal.FinalMergePreconditions.Read;
import vn.vibefix.repo.internal.FinalMerges.FinalMerge;
import vn.vibefix.scanning.SensitiveChanges;
import vn.vibefix.scanning.SensitiveChanges.SensitiveChange;

/**
 * {@value FinalMerges#CHECK} (FR-37, AD-11, SG-19): before the Customer confirms, the preconditions, the
 * whole Job's Sensitive Changes (from the J7 commit to the Intermediary head), the workflow files already
 * in the Source Repository, and the trial merge in the sandbox. A conflict or a failed guard routes to
 * the ZIP Fallback with nothing pushed; a Customer to wait for, an open grant, or a frozen Job defers.
 * Collaborators are looked up on use.
 */
@Component
class FinalMergeCheckOperation implements OperationHandler {

	static final Duration CUSTOMER_WAIT = Duration.ofMinutes(30);

	static final Duration SHORT_WAIT = Duration.ofMinutes(10);

	private final ObjectProvider<FinalMerges> merges;

	private final ObjectProvider<FinalMergePreconditions> preconditions;

	private final ObjectProvider<FinalMergeTokens> tokens;

	private final ObjectProvider<MergeApp> app;

	private final ObjectProvider<PlatformRepositories> repositories;

	private final ObjectProvider<SensitiveChanges> sensitive;

	private final ObjectProvider<JobBusy> busy;

	private final Clock clock;

	FinalMergeCheckOperation(ObjectProvider<FinalMerges> merges, ObjectProvider<FinalMergePreconditions> preconditions,
			ObjectProvider<FinalMergeTokens> tokens, ObjectProvider<MergeApp> app,
			ObjectProvider<PlatformRepositories> repositories, ObjectProvider<SensitiveChanges> sensitive,
			ObjectProvider<JobBusy> busy, Clock clock) {
		this.merges = merges;
		this.preconditions = preconditions;
		this.tokens = tokens;
		this.app = app;
		this.repositories = repositories;
		this.sensitive = sensitive;
		this.busy = busy;
		this.clock = clock;
	}

	@Override
	public String type() {
		return FinalMerges.CHECK;
	}

	@Override
	public Lane lane() {
		return Lane.GITHUB_LONG;
	}

	@Override
	public Outcome run(OperationRun run) {
		UUID authorizationId = UUID.fromString(String.valueOf(run.payload().get("authorizationId")));
		FinalMerges merges = this.merges.getObject();
		FinalMerge merge = merges.find(authorizationId).filter(found -> "CHECKING".equals(found.status())).orElse(null);
		BusyJob job = merge == null ? null : this.busy.getObject().job(merge.jobId()).orElse(null);
		if (merge == null || job == null || !"CLOSING".equals(job.state())) {
			return new Outcome.Obsolete("NOT_CHECKING");
		}
		if (job.frozen()) {
			return new Outcome.Deferred(this.clock.instant().plus(SHORT_WAIT));
		}
		try {
			Read read = this.preconditions.getObject().read(merge.jobId(), merge.productId());
			switch (read.verdict()) {
				case WAIT_FOR_CUSTOMER -> {
					return new Outcome.Deferred(this.clock.instant().plus(CUSTOMER_WAIT));
				}
				case WAIT_FOR_REVOCATION -> {
					return new Outcome.Deferred(this.clock.instant().plus(SHORT_WAIT));
				}
				case SOURCE_GONE -> {
					merges.fallback(authorizationId, "REFUSED", "SOURCE_NOT_PUBLIC");
					return new Outcome.Aborted("SOURCE_NOT_PUBLIC");
				}
				case GUARD_FAILED -> {
					merges.fallback(authorizationId, "REFUSED", "J9_GUARD");
					return new Outcome.Aborted("J9_GUARD");
				}
				case HOLD -> {
				}
			}
			String head = Objects.requireNonNull(read.head());
			var setup = Objects.requireNonNull(read.setup());
			var source = Objects.requireNonNull(read.source());
			String approved = Objects.requireNonNull(setup.approvedSha());
			List<SensitiveChange> changes = head.equals(approved) ? List.of()
					: this.sensitive.getObject()
						.classify(this.repositories.getObject().compare(Objects.requireNonNull(read.intermediary()), approved, head)
							.files());
			InstallationToken token = this.tokens.getObject().mint(authorizationId, Map.of("contents", "read")).orElse(null);
			if (token == null) {
				return new Outcome.Deferred(this.clock.instant().plus(SHORT_WAIT));
			}
			List<String> workflows;
			try {
				workflows = this.app.getObject().workflowFiles(token, source.repositoryFullName(), source.defaultBranch());
			}
			finally {
				this.app.getObject().revoke(token);
			}
			TrialMerge trial = this.app.getObject()
				.trialMerge(source.repositoryFullName(), source.defaultBranch(),
						Objects.requireNonNull(read.intermediary()).fullName(), Objects.requireNonNull(setup.defaultBranch()),
						head);
			if (trial.moved()) {
				return new Outcome.Transient("INTERMEDIARY_MOVED");
			}
			if (!trial.clean()) {
				merges.fallback(authorizationId, "CONFLICT", "TRIAL_CONFLICT");
				return new Outcome.Aborted("TRIAL_CONFLICT");
			}
			merges.ready(authorizationId, head, source.defaultBranch(), changes, workflows);
			return Outcome.succeeded();
		}
		catch (GitHubException failed) {
			return new Outcome.Transient(failed.status() == 0 ? "GITHUB_UNAVAILABLE" : "GITHUB_HTTP_" + failed.status());
		}
	}

}
