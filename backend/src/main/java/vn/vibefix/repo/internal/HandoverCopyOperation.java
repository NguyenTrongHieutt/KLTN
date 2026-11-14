package vn.vibefix.repo.internal;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import vn.vibefix.github.ActionsRuns;
import vn.vibefix.github.ActionsRuns.Correlation;
import vn.vibefix.github.ActionsRuns.Dispatch;
import vn.vibefix.github.ActionsRuns.Dispatched;
import vn.vibefix.github.ActionsRuns.Operation;
import vn.vibefix.github.ActionsRuns.Report;
import vn.vibefix.github.ActionsRuns.Run;
import vn.vibefix.github.ActionsRuns.RunStatus;
import vn.vibefix.github.ActionsRuns.WorkflowRefused;
import vn.vibefix.github.GitHubApps.App;
import vn.vibefix.github.GitHubException;
import vn.vibefix.github.PlatformRepositories;
import vn.vibefix.github.PlatformRepositories.PlatformRepository;
import vn.vibefix.job.JobBusy;
import vn.vibefix.job.JobBusy.BusyJob;
import vn.vibefix.operations.Lane;
import vn.vibefix.operations.OperationHandler;
import vn.vibefix.operations.OperationPolicy;
import vn.vibefix.operations.OperationRun;
import vn.vibefix.operations.Outcome;
import vn.vibefix.repo.HandoverCopies.Refusal;
import vn.vibefix.repo.internal.JobHandoverCopies.Copy;
import vn.vibefix.repo.internal.JobSetups.Setup;

/**
 * {@value JobHandoverCopies#TYPE} (FR-32, AD-11, AD-44, AD-46, Story 23.8a): the handover copy as an External
 * Operation of the GitHub-long lane, with the policy of J7's copy stage and no busy marker, since the team keeps
 * working while the runner copies (AD-46). Each attempt resumes at the first unfinished step:
 * <ol>
 * <li>{@code handover-copy} dispatched on the runner through the Platform App, with the Working Repository, its
 * default branch, the range's start, the Handover Commit, the Intermediary and the new
 * {@code handover/{submissionId}} branch, and a grant of the two Job repositories; the exchange correlation is
 * recorded;</li>
 * <li>the run polled every {@link #RUN_POLL} for its first {@link #RUN_POLL_FAST}, then every
 * {@link #RUN_POLL_LATER}, up to {@link ActionsRuns#RUN_TIMEOUT}, when its correlation is closed and its run
 * cancelled;</li>
 * <li>the run's path report read: a report that says {@code .github/workflows} changed refuses the handover
 * {@link Refusal#WORKFLOW_FILES_CHANGED}, whatever the run concluded, since the run pushes nothing then;</li>
 * <li>{@code handover/{submissionId}} read back through the Platform App at exactly the Handover Commit, which a
 * later attempt reads before it dispatches again, since the workflow's new-branch guard refuses a second run once
 * the branch exists;</li>
 * <li>the System's pull request opened into the Intermediary's default branch, which is not always {@code main};</li>
 * <li>the copy resolved, which releases the marker and tells {@code delivery}.</li>
 * </ol>
 * Outcomes: the exchange's refusal ends it {@link Refusal#EXCHANGE_REFUSED}; a report missing or unreadable ends it
 * {@link Refusal#PATH_LIST_UNREADABLE}; a failed run, the timeout, or a branch that does not read back is tried
 * again under the lane policy and ends {@link Refusal#RUN_FAILED} once the attempts run out, which leaves the
 * operation in FR-64. A frozen Job only waits (AD-28), and vibefix copies nothing itself.
 */
@Component
class HandoverCopyOperation implements OperationHandler {

	/** J7's copy stage's policy (AD-46). */
	static final OperationPolicy POLICY = JobSetupOperation.POLICY;

	/** How long a frozen Job's copy waits before it looks again (AD-28). */
	static final Duration FROZEN_WAIT = JobSetupOperation.FROZEN_WAIT;

	/** How often a dispatched copy run is read while it is queued or in progress, at first (AD-46). */
	static final Duration RUN_POLL = JobSetupOperation.RUN_POLL;

	/** How long after its dispatch a copy run is read every {@link #RUN_POLL}. */
	static final Duration RUN_POLL_FAST = JobSetupOperation.RUN_POLL_FAST;

	/** How often it is read after that, until {@link ActionsRuns#RUN_TIMEOUT}. */
	static final Duration RUN_POLL_LATER = JobSetupOperation.RUN_POLL_LATER;

	/** What a run is called once vibefix stopped waiting for it after {@link ActionsRuns#RUN_TIMEOUT}. */
	static final String TIMED_OUT = JobSetupOperation.TIMED_OUT;

	/** What a successful run is called when its branch does not read back at the Handover Commit. */
	static final String NOT_READ_BACK = JobSetupOperation.NOT_READ_BACK;

	private static final Logger log = LoggerFactory.getLogger(HandoverCopyOperation.class);

	/** Looked up on use: {@code operations} collects its handlers before the modules they call exist. */
	private final ObjectProvider<JobHandoverCopies> copiesProvider;

	private final ObjectProvider<JobSetups> setupsProvider;

	private final ObjectProvider<JobBusy> busyProvider;

	private final ObjectProvider<PlatformRepositories> repositoriesProvider;

	private final ObjectProvider<ActionsRuns> runsProvider;

	private final Clock clock;

	HandoverCopyOperation(ObjectProvider<JobHandoverCopies> copies, ObjectProvider<JobSetups> setups,
			ObjectProvider<JobBusy> busy, ObjectProvider<PlatformRepositories> repositories,
			ObjectProvider<ActionsRuns> runs, Clock clock) {
		this.copiesProvider = copies;
		this.setupsProvider = setups;
		this.busyProvider = busy;
		this.repositoriesProvider = repositories;
		this.runsProvider = runs;
		this.clock = clock;
	}

	@Override
	public String type() {
		return JobHandoverCopies.TYPE;
	}

	@Override
	public Lane lane() {
		return Lane.GITHUB_LONG;
	}

	@Override
	public OperationPolicy policy() {
		return POLICY;
	}

	@Override
	public Outcome run(OperationRun run) {
		Copy copy = copies().find(run.key()).orElse(null);
		if (copy == null) {
			return new Outcome.Obsolete("COPY_GONE");
		}
		if (!"RUNNING".equals(copy.status())) {
			return new Outcome.Obsolete("COPY_ENDED");
		}
		BusyJob job = busy().job(copy.jobId()).orElse(null);
		if (job == null || !"ACTIVE".equals(job.state())) {
			return new Outcome.Obsolete("JOB_NOT_ACTIVE");
		}
		if (job.frozen()) {
			return new Outcome.Deferred(this.clock.instant().plus(FROZEN_WAIT));
		}
		Setup setup = done(copy.jobId());
		if (setup == null) {
			return new Outcome.Obsolete("SETUP_GONE");
		}
		try {
			return copy.correlationId() == null || copy.runSpent() ? again(copy, setup) : poll(copy, setup);
		}
		catch (WorkflowRefused refused) {
			// The runner's own workflow file is not the published one: nothing was recorded or dispatched.
			log.warn("Handover copy {}: the runner's workflow is {}", copy.submissionId(), refused.refusal());
			return new Outcome.Transient(refused.refusal().name());
		}
		catch (GitHubException failed) {
			// The message names the step and GitHub's status, never a token.
			log.warn("Handover copy {} of Job {}: {}", copy.submissionId(), copy.jobId(), failed.getMessage());
			return new Outcome.Transient(failed.status() == 0 ? "GITHUB_UNAVAILABLE" : "GITHUB_HTTP_" + failed.status());
		}
	}

	/**
	 * A run to dispatch, or a push a spent run already made: the workflow's new-branch guard refuses every later
	 * run once {@code handover/{id}} exists, so a branch that already holds the Handover Commit carries on at the
	 * report and the pull request instead of being copied again.
	 */
	private Outcome again(Copy copy, Setup setup) {
		if (copy.correlationId() != null && copy.handoverCommit().equals(branchHead(copy, setup))) {
			log.info("Handover copy {}: its spent run had pushed {} already; the pull request follows",
					copy.submissionId(), copy.branch());
			return reported(copy, setup);
		}
		return dispatch(copy, setup);
	}

	/** Step 1: the copy dispatched on the runner, with a grant of the Job's two repositories. */
	private Outcome dispatch(Copy copy, Setup setup) {
		PlatformRepositories repositories = repositories();
		PlatformRepository runner = repositories.runner().orElse(null);
		if (runner == null) {
			// AD-46: Actions run on the runner alone, so a missing runner waits for the organization to be fixed.
			log.warn("Handover copy {} waits: no runner is configured", copy.submissionId());
			return new Outcome.Deferred(this.clock.instant().plus(FROZEN_WAIT));
		}
		PlatformRepository working = Objects.requireNonNull(setup.working());
		PlatformRepository intermediary = Objects.requireNonNull(setup.intermediary());
		Map<String, String> inputs = new LinkedHashMap<>();
		inputs.put("source_repository", working.fullName());
		inputs.put("source_branch", Objects.requireNonNull(setup.defaultBranch()));
		inputs.put("from_sha", copy.fromSha());
		inputs.put("sha", copy.handoverCommit());
		inputs.put("target_repository", intermediary.fullName());
		inputs.put("target_branch", copy.branch());
		Dispatched dispatched = runs().dispatch(new Dispatch(App.PLATFORM, null, Operation.HANDOVER_COPY, runner.id(),
				repositories.runnerWorkflow(), inputs, Set.of(working.id(), intermediary.id()), null));
		if (!copies().dispatched(copy, dispatched.correlation())) {
			// The copy ended meanwhile: its run is given up, so it never exchanges.
			runs().close(dispatched.correlation());
			return new Outcome.Obsolete("COPY_ENDED");
		}
		return new Outcome.Deferred(this.clock.instant().plus(RUN_POLL));
	}

	/** Steps 2 to 6: the run read, its report, the branch read back, and the pull request. */
	private Outcome poll(Copy copy, Setup setup) {
		UUID correlationId = Objects.requireNonNull(copy.correlationId());
		Outcome refused = exchangeRefused(copy, correlationId);
		if (refused != null) {
			return refused;
		}
		Run run = runs().run(correlationId).orElse(null);
		Instant now = this.clock.instant();
		Instant dispatchedAt = Objects.requireNonNull(copy.dispatchedAt());
		Instant timeout = dispatchedAt.plus(ActionsRuns.RUN_TIMEOUT);
		if (run == null || run.status() != RunStatus.COMPLETED) {
			boolean late = !now.isBefore(timeout);
			copies().runRead(copy, run == null ? null : run.status().name(), late ? TIMED_OUT : null);
			if (late) {
				log.warn("Handover copy {}: the run under correlation {} did not complete within {}; it is given up",
						copy.submissionId(), correlationId, ActionsRuns.RUN_TIMEOUT);
				// Closed and cancelled, so it never trades for a token beside the run that replaces it.
				runs().close(correlationId);
				return new Outcome.Transient(Refusal.RUN_FAILED.name());
			}
			Instant next = now.plus(now.isBefore(dispatchedAt.plus(RUN_POLL_FAST)) ? RUN_POLL : RUN_POLL_LATER);
			return new Outcome.Deferred(next.isAfter(timeout) ? timeout : next);
		}
		String conclusion = run.conclusion() == null ? "unknown" : run.conclusion();
		copies().runRead(copy, RunStatus.COMPLETED.name(), conclusion);
		Report report = runs().report(correlationId).orElse(null);
		if (report != null && report.workflowsChanged()) {
			// N-16: the run reported the change and pushed nothing, whatever it then concluded.
			return end(copy, Refusal.WORKFLOW_FILES_CHANGED);
		}
		if (!"success".equals(conclusion)) {
			// A refusal that landed after the first read failed the run: it decides, not the conclusion.
			Outcome late = exchangeRefused(copy, correlationId);
			if (late != null) {
				return late;
			}
			log.warn("Handover copy {}: the run {} concluded {}", copy.submissionId(), run.id(), conclusion);
			return new Outcome.Transient(Refusal.RUN_FAILED.name());
		}
		if (report == null) {
			log.warn("Handover copy {}: the run {} succeeded but reported no changed paths", copy.submissionId(), run.id());
			return end(copy, Refusal.PATH_LIST_UNREADABLE);
		}
		String head = branchHead(copy, setup);
		if (!copy.handoverCommit().equals(head)) {
			// The run is spent: the next attempt reads the branch again, and the attempts running out end it RUN_FAILED.
			log.warn("Handover copy {}: {} reads back {} on {} instead of the Handover Commit", copy.submissionId(),
					Objects.requireNonNull(setup.intermediary()).fullName(), head, copy.branch());
			copies().runRead(copy, RunStatus.COMPLETED.name(), NOT_READ_BACK);
			return new Outcome.Transient(Refusal.RUN_FAILED.name());
		}
		return pullRequest(copy, setup);
	}

	/**
	 * Steps 3 to 6 for a branch that already holds the Handover Commit: the report read, then the pull request. A
	 * push only ever follows the report, so a branch with no report at all is an unreadable list.
	 */
	private Outcome reported(Copy copy, Setup setup) {
		Report report = runs().report(Objects.requireNonNull(copy.correlationId())).orElse(null);
		if (report == null) {
			log.warn("Handover copy {}: {} is pushed but no changed paths were reported", copy.submissionId(),
					copy.branch());
			return end(copy, Refusal.PATH_LIST_UNREADABLE);
		}
		if (report.workflowsChanged()) {
			return end(copy, Refusal.WORKFLOW_FILES_CHANGED);
		}
		return pullRequest(copy, setup);
	}

	/** Steps 5 and 6: the System's pull request into the Intermediary's default branch, then the resolution. */
	private Outcome pullRequest(Copy copy, Setup setup) {
		PlatformRepository intermediary = Objects.requireNonNull(setup.intermediary());
		int pull = copy.pullNumber() != null ? copy.pullNumber()
				: repositories().openPullRequest(intermediary, copy.branch(), Objects.requireNonNull(setup.defaultBranch()),
						copy.pullTitle(), copy.pullBody());
		copies().pullOpened(copy, pull);
		copies().finish(copy.operationKey(), null);
		return new Outcome.Succeeded(Map.of("pull", pull));
	}

	/** What {@code handover/{id}} holds on the Intermediary now; {@code "none"} for a branch GitHub does not show. */
	private String branchHead(Copy copy, Setup setup) {
		try {
			return repositories().branchHead(Objects.requireNonNull(setup.intermediary()), copy.branch());
		}
		catch (GitHubException missing) {
			if (missing.status() != 404) {
				throw missing;
			}
			return "none";
		}
	}

	/**
	 * The exchange's refusal of the run, read now: the copy ends {@link Refusal#EXCHANGE_REFUSED}, since a run
	 * without a token copies nothing and a second run would be refused the same way.
	 * @return what the refusal decides, or none while the correlation is not refused
	 */
	private @Nullable Outcome exchangeRefused(Copy copy, UUID correlationId) {
		Correlation correlation = runs().correlation(correlationId).orElse(null);
		if (correlation == null || correlation.status() != ActionsRuns.Status.REFUSED) {
			return null;
		}
		log.warn("Handover copy {}: the exchange refused correlation {}: {}", copy.submissionId(), correlationId,
				correlation.refusal() == null ? "unknown" : correlation.refusal().code());
		return end(copy, Refusal.EXCHANGE_REFUSED);
	}

	/** The copy ends with its reason: the marker goes, {@code delivery} hears it, and nothing is tried again. */
	private Outcome end(Copy copy, Refusal refusal) {
		copies().finish(copy.operationKey(), refusal);
		return new Outcome.Aborted(refusal.name());
	}

	/** The Job's done Setup, which names the two repositories and the Working default branch. */
	private @Nullable Setup done(UUID jobId) {
		return setups().latest(jobId)
			.filter(setup -> "DONE".equals(setup.status()) && setup.working() != null && setup.intermediary() != null
					&& setup.defaultBranch() != null)
			.orElse(null);
	}

	private JobHandoverCopies copies() {
		return this.copiesProvider.getObject();
	}

	private JobSetups setups() {
		return this.setupsProvider.getObject();
	}

	private JobBusy busy() {
		return this.busyProvider.getObject();
	}

	private PlatformRepositories repositories() {
		return this.repositoriesProvider.getObject();
	}

	private ActionsRuns runs() {
		return this.runsProvider.getObject();
	}

}
