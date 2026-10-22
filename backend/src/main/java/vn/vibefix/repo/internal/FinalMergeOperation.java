package vn.vibefix.repo.internal;

import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import vn.vibefix.github.GitHubApps.InstallationToken;
import vn.vibefix.github.GitHubException;
import vn.vibefix.github.MergeApp;
import vn.vibefix.github.MergeApp.MergeAnswer;
import vn.vibefix.github.MergeApp.PullRequest;
import vn.vibefix.job.JobBusy;
import vn.vibefix.job.JobBusy.BusyJob;
import vn.vibefix.operations.Lane;
import vn.vibefix.operations.OperationHandler;
import vn.vibefix.operations.OperationRun;
import vn.vibefix.operations.Outcome;
import vn.vibefix.repo.internal.FinalMergeAuthorizations.Authorization;
import vn.vibefix.repo.internal.FinalMergePreconditions.Read;
import vn.vibefix.repo.internal.FinalMerges.FinalMerge;

/**
 * {@value FinalMerges#MERGE} (FR-37, AD-5, AD-10, AD-11, SG-15): the precondition operation the Customer's
 * confirmation requests, under the {@code FINAL_MERGE} busy marker; a frozen Job waits. It checks the
 * preconditions again and that the Intermediary head is still the one confirmed, mints the Final Merge
 * token through Story 8.7's gate (narrowed to the Source Repository), pushes the head to a new branch never
 * forced, opens or reuses its pull request, merges it with a merge commit, reads {@code merged} back, and
 * deletes the branch; the resolving transaction applies J9. An archived repository, a ruleset, or
 * required checks route to the ZIP Fallback instead of the failed-operations queue; transient errors
 * retry. Collaborators are looked up on use.
 */
@Component
class FinalMergeOperation implements OperationHandler {

	static final Duration FROZEN_WAIT = Duration.ofMinutes(15);

	static final String BRANCH_PREFIX = "vibefix/final-merge-";

	private static final Logger log = LoggerFactory.getLogger(FinalMergeOperation.class);

	private final ObjectProvider<FinalMerges> merges;

	private final ObjectProvider<FinalMergeAuthorizations> authorizations;

	private final ObjectProvider<FinalMergePreconditions> preconditions;

	private final ObjectProvider<FinalMergeTokens> tokens;

	private final ObjectProvider<MergeAppBindings> bindings;

	private final ObjectProvider<MergeApp> app;

	private final ObjectProvider<JobBusy> busy;

	private final Clock clock;

	FinalMergeOperation(ObjectProvider<FinalMerges> merges, ObjectProvider<FinalMergeAuthorizations> authorizations,
			ObjectProvider<FinalMergePreconditions> preconditions, ObjectProvider<FinalMergeTokens> tokens,
			ObjectProvider<MergeAppBindings> bindings, ObjectProvider<MergeApp> app, ObjectProvider<JobBusy> busy,
			Clock clock) {
		this.merges = merges;
		this.authorizations = authorizations;
		this.preconditions = preconditions;
		this.tokens = tokens;
		this.bindings = bindings;
		this.app = app;
		this.busy = busy;
		this.clock = clock;
	}

	@Override
	public String type() {
		return FinalMerges.MERGE;
	}

	@Override
	public Lane lane() {
		return Lane.GITHUB_LONG;
	}

	@Override
	public Outcome run(OperationRun run) {
		UUID authorizationId = UUID.fromString(String.valueOf(run.payload().get("authorizationId")));
		FinalMerges merges = this.merges.getObject();
		FinalMerge merge = merges.find(authorizationId)
			.filter(found -> "CONFIRMED".equals(found.status()) && run.key().equals(found.operationKey()))
			.orElse(null);
		BusyJob job = merge == null ? null : this.busy.getObject().job(merge.jobId()).orElse(null);
		if (merge == null || job == null
				|| job.busy().filter(marker -> marker.operationKey().equals(run.key())).isEmpty()) {
			return new Outcome.Obsolete("NOT_CONFIRMED");
		}
		if (job.frozen()) {
			return new Outcome.Deferred(this.clock.instant().plus(FROZEN_WAIT));
		}
		Authorization authorization = this.authorizations.getObject().find(authorizationId).orElseThrow();
		MergeApp app = this.app.getObject();
		try {
			Read read = this.preconditions.getObject().read(merge.jobId(), merge.productId());
			switch (read.verdict()) {
				case WAIT_FOR_CUSTOMER -> {
					return new Outcome.Deferred(this.clock.instant().plus(FinalMergeCheckOperation.CUSTOMER_WAIT));
				}
				case WAIT_FOR_REVOCATION -> {
					return new Outcome.Deferred(this.clock.instant().plus(FinalMergeCheckOperation.SHORT_WAIT));
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
			if (!head.equals(merge.intermediarySha())) {
				merges.fallback(authorizationId, "REFUSED", "J9_GUARD");
				return new Outcome.Aborted("J9_GUARD");
			}
			String source = authorization.repositoryName();
			String base = Objects.requireNonNull(read.source()).defaultBranch();
			InstallationToken token = this.tokens.getObject()
				.mint(authorizationId, Map.of("contents", "write", "pull_requests", "write"))
				.orElse(null);
			if (token == null) {
				return new Outcome.Transient("TOKEN_REFUSED");
			}
			try {
				String branch = merge.branch() != null ? merge.branch() : BRANCH_PREFIX + authorizationId;
				PullRequest pull = merge.pullNumber() != null
						? new PullRequest(merge.pullNumber(), Objects.requireNonNullElse(merge.pullUrl(), ""))
						: app.findPull(token, source, branch).orElse(null);
				if (pull == null) {
					if (!app.push(token, Objects.requireNonNull(read.intermediary()).fullName(),
							Objects.requireNonNull(Objects.requireNonNull(read.setup()).defaultBranch()), head, source, branch)) {
						merges.fallback(authorizationId, "REFUSED", "PUSH_REFUSED");
						return new Outcome.Aborted("PUSH_REFUSED");
					}
					pull = app.openPull(token, source, branch, base, "vibefix: hợp nhất mã của " + job.title(),
							"Mã đã nghiệm thu của công việc " + job.title() + " trên vibefix.");
				}
				merges.pulled(authorizationId, branch, pull);
				MergeAnswer answer = app.mergePull(token, source, pull.number(), head, "vibefix: hợp nhất mã của " + job.title());
				if (answer == MergeAnswer.REFUSED) {
					merges.fallback(authorizationId, "REFUSED", "MERGE_REFUSED");
					return new Outcome.Aborted("MERGE_REFUSED");
				}
				if (answer == MergeAnswer.TRANSIENT || !app.pullMerged(token, source, pull.number())) {
					return new Outcome.Transient("NOT_MERGED_YET");
				}
				try {
					app.deleteBranch(token, source, branch);
				}
				catch (GitHubException kept) {
					log.warn("The Final Merge branch {} of {} was not deleted: {}", branch, source, kept.getMessage());
				}
			}
			finally {
				app.revoke(token);
			}
		}
		catch (GitHubException failed) {
			return new Outcome.Transient(failed.status() == 0 ? "GITHUB_UNAVAILABLE" : "GITHUB_HTTP_" + failed.status());
		}
		merges.merged(authorizationId, run.key());
		removeInstallation(authorization);
		return Outcome.succeeded();
	}

	/** AD-10: the installation goes unless another open authorization needs it. */
	private void removeInstallation(Authorization authorization) {
		if (authorization.installationId() == null) {
			return;
		}
		try {
			this.bindings.getObject()
				.read(authorization.installationId())
				.ifPresent(installation -> this.bindings.getObject().removeIfUnneeded(installation));
		}
		catch (GitHubException kept) {
			// The daily reconciliation removes it.
			log.warn("Merge App installation {} was not removed yet: {}", authorization.installationId(), kept.getMessage());
		}
	}

}
