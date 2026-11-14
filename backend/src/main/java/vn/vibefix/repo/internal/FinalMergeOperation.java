package vn.vibefix.repo.internal;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
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
import vn.vibefix.github.ActionsRuns.Run;
import vn.vibefix.github.ActionsRuns.RunMarker;
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
import vn.vibefix.product.ProductSources;
import vn.vibefix.product.ProductSources.DispatchTarget;
import vn.vibefix.product.ProductSources.SourceBranch;
import vn.vibefix.repo.internal.FinalMergePreconditions.Read;
import vn.vibefix.repo.internal.FinalMerges.FinalMerge;

/**
 * {@value FinalMerges#MERGE} (FR-37, AD-5, AD-11, AD-44, AD-46, R1, D7, Story 23.9a): the precondition operation the
 * Customer's N-18 confirmation requests, under the {@code FINAL_MERGE} busy marker. vibefix never writes to a Customer
 * repository: the accepted code reaches their project through one run of the published {@code vibefix.yml} on their
 * own GitHub Actions, and they merge the branch themselves on GitHub. Each attempt resumes at the first unfinished
 * step:
 * <ol>
 * <li>the preconditions read again (the Source App binding, the admin right and {@code vibefix.yml} through
 * {@code product}, every Mentor's and Crew member's revocation, J9's guard at the head that was checked) and the two
 * {@code .github/workflows/} trees compared, so the Customer spends no minute of their own Actions on a run GitHub
 * would refuse ({@code WORKFLOW_FILES_CHANGED}, deferred-work 626);</li>
 * <li>{@code vibefix/final-{jobId}} read through the Source App first: at the merge commit the run already pushed it,
 * and at any other commit the Final Merge ends {@code BRANCH_EXISTS}, since the run refuses an existing branch;</li>
 * <li>{@code final-merge} dispatched in the Customer's Source Repository through their Source App installation, with
 * the merge commit, the new branch, the Intermediary and a grant of the Intermediary alone (Contents read, AD-44);
 * the exchange correlation is recorded;</li>
 * <li>the run polled every {@link #RUN_POLL} for its first {@link #RUN_POLL_FAST}, then every {@link #RUN_POLL_LATER},
 * up to {@link ActionsRuns#RUN_TIMEOUT}, when its correlation is closed and its run cancelled;</li>
 * <li>the branch read back through the Source App at exactly the merge commit; only then does J9 apply, with
 * {@code repo.final_branch_ready} for both sides.</li>
 * </ol>
 * Outcomes: the exchange's refusal ends it {@code EXCHANGE_REFUSED}; a run Actions' quota stopped ends it
 * {@code ACTIONS_QUOTA}; a failed guard ends it {@code J9_GUARD}; any other conclusion, the timeout, or a success whose
 * branch does not read back is {@code RUN_FAILED}, which the lane policy dispatches again under a new correlation
 * until its attempts run out, and then ends it. Every ending releases the marker and hands the Job to the closure
 * fallback with that reason. A Source block the Customer can fix only waits, as does a frozen Job (AD-28, Story 9.4).
 */
@Component
class FinalMergeOperation implements OperationHandler {

	/** J7's copy stage's policy (AD-46). */
	static final OperationPolicy POLICY = JobSetupOperation.POLICY;

	/** How long a frozen Job's Final Merge waits before it looks again (AD-28, Story 9.4). */
	static final Duration FROZEN_WAIT = JobSetupOperation.FROZEN_WAIT;

	/** How often a dispatched run is read while it is queued or in progress, at first (AD-46). */
	static final Duration RUN_POLL = JobSetupOperation.RUN_POLL;

	/** How long after its dispatch the run is read every {@link #RUN_POLL}. */
	static final Duration RUN_POLL_FAST = JobSetupOperation.RUN_POLL_FAST;

	/** How often it is read after that, until {@link ActionsRuns#RUN_TIMEOUT}. */
	static final Duration RUN_POLL_LATER = JobSetupOperation.RUN_POLL_LATER;

	/** What a run is called once vibefix stopped waiting for it after {@link ActionsRuns#RUN_TIMEOUT}. */
	static final String TIMED_OUT = JobSetupOperation.TIMED_OUT;

	/** What a successful run is called when its branch does not read back at the merge commit. */
	static final String NOT_READ_BACK = JobSetupOperation.NOT_READ_BACK;

	private static final Logger log = LoggerFactory.getLogger(FinalMergeOperation.class);

	/** Looked up on use: {@code operations} collects its handlers before the modules they call exist. */
	private final ObjectProvider<FinalMerges> mergesProvider;

	private final ObjectProvider<FinalMergePreconditions> preconditionsProvider;

	private final ObjectProvider<ProductSources> sourcesProvider;

	private final ObjectProvider<PlatformRepositories> repositoriesProvider;

	private final ObjectProvider<ActionsRuns> runsProvider;

	private final ObjectProvider<JobBusy> busyProvider;

	private final Clock clock;

	FinalMergeOperation(ObjectProvider<FinalMerges> merges, ObjectProvider<FinalMergePreconditions> preconditions,
			ObjectProvider<ProductSources> sources, ObjectProvider<PlatformRepositories> repositories,
			ObjectProvider<ActionsRuns> runs, ObjectProvider<JobBusy> busy, Clock clock) {
		this.mergesProvider = merges;
		this.preconditionsProvider = preconditions;
		this.sourcesProvider = sources;
		this.repositoriesProvider = repositories;
		this.runsProvider = runs;
		this.busyProvider = busy;
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
	public OperationPolicy policy() {
		return POLICY;
	}

	@Override
	public Outcome run(OperationRun run) {
		UUID authorizationId = UUID.fromString(String.valueOf(run.payload().get("authorizationId")));
		FinalMerge merge = merges().find(authorizationId)
			.filter(found -> FinalMerges.confirmed(found) && run.key().equals(found.operationKey()))
			.orElse(null);
		BusyJob job = merge == null ? null : busy().job(merge.jobId()).orElse(null);
		if (merge == null || job == null
				|| job.busy().filter(marker -> marker.operationKey().equals(run.key())).isEmpty()) {
			return new Outcome.Obsolete("NOT_CONFIRMED");
		}
		if (job.frozen()) {
			// Story 9.4: an open Dispute pauses the dispatch, as it pauses the whole Final Merge.
			return new Outcome.Deferred(this.clock.instant().plus(FROZEN_WAIT));
		}
		try {
			Read read = preconditions().read(merge.jobId(), merge.productId());
			switch (read.verdict()) {
				case WAIT_FOR_CUSTOMER -> {
					return new Outcome.Deferred(this.clock.instant().plus(FinalMergeCheckOperation.CUSTOMER_WAIT));
				}
				case WAIT_FOR_REVOCATION -> {
					return new Outcome.Deferred(this.clock.instant().plus(FinalMergeCheckOperation.SHORT_WAIT));
				}
				case GUARD_FAILED -> {
					return end(merge, "J9_GUARD");
				}
				case HOLD -> {
				}
			}
			String head = Objects.requireNonNull(read.head());
			if (!head.equals(merge.intermediarySha())) {
				// The Intermediary moved after the check: J9's guard decides on the head the Customer confirmed for.
				return end(merge, "J9_GUARD");
			}
			return merge.correlationId() == null || merge.runSpent() ? again(merge, read, head) : poll(merge, head);
		}
		catch (WorkflowRefused refused) {
			// The Customer's vibefix.yml is no published version now: they can add it again before the deadline.
			log.warn("Final Merge {}: the Source Repository's workflow is {}", merge.authorizationId(), refused.refusal());
			return new Outcome.Deferred(this.clock.instant().plus(FinalMergeCheckOperation.CUSTOMER_WAIT));
		}
		catch (GitHubException failed) {
			// The message names the step and GitHub's status, never a token.
			log.warn("Final Merge {} of Job {}: {}", merge.authorizationId(), merge.jobId(), failed.getMessage());
			return new Outcome.Transient(failed.status() == 0 ? "GITHUB_UNAVAILABLE" : "GITHUB_HTTP_" + failed.status());
		}
	}

	/**
	 * Step 2: the branch read before a dispatch. The run refuses an existing {@code vibefix/final-{jobId}}, so a push
	 * vibefix never observed would otherwise fail the next run for the wrong reason: at the merge commit the Final
	 * Merge is done, and at any other commit it ends {@code BRANCH_EXISTS}.
	 */
	private Outcome again(FinalMerge merge, Read read, String head) {
		List<String> workflows = FinalMergeCheckOperation.workflowFiles(sources(), repositories(), merge.productId(),
				Objects.requireNonNull(read.intermediary()), head).orElse(null);
		if (workflows == null) {
			// The Source App no longer reaches the Source Repository: the Customer fixes it, and nothing ends.
			return new Outcome.Deferred(this.clock.instant().plus(FinalMergeCheckOperation.CUSTOMER_WAIT));
		}
		if (!workflows.isEmpty()) {
			// deferred-work 626: a run's GITHUB_TOKEN can never push a workflow file, so nothing is dispatched. The
			// trees are compared before each dispatch alone, never on every poll of a run that is already out.
			return end(merge, "WORKFLOW_FILES_CHANGED");
		}
		String branch = FinalMerges.branch(merge.jobId());
		SourceBranch existing = sources().branch(merge.productId(), branch);
		if (existing.block() != null) {
			return new Outcome.Deferred(this.clock.instant().plus(FinalMergeCheckOperation.CUSTOMER_WAIT));
		}
		if (existing.sha() != null) {
			if (head.equals(existing.sha())) {
				log.info("Final Merge {}: {} already holds the merge commit; J9 follows", merge.authorizationId(), branch);
				return ready(merge, branch);
			}
			log.warn("Final Merge {}: {} already exists in the Source Repository at another commit", merge.authorizationId(),
					branch);
			return end(merge, "BRANCH_EXISTS");
		}
		return dispatch(merge, read, head, branch);
	}

	/** Step 3: {@code final-merge} dispatched through the Customer's own Source App installation. */
	private Outcome dispatch(FinalMerge merge, Read read, String head, String branch) {
		DispatchTarget target = sources().dispatchTarget(merge.productId());
		if (target.block() != null) {
			return new Outcome.Deferred(this.clock.instant().plus(FinalMergeCheckOperation.CUSTOMER_WAIT));
		}
		PlatformRepository intermediary = Objects.requireNonNull(read.intermediary());
		Map<String, String> inputs = new LinkedHashMap<>();
		inputs.put("operation", Operation.FINAL_MERGE.code());
		inputs.put("sha", head);
		inputs.put("branch", branch);
		inputs.put("intermediary", intermediary.fullName());
		// working stays empty: the final-merge job refuses a Working Repository.
		Dispatched dispatched = runs().dispatch(new Dispatch(App.SOURCE, target.installationId(), Operation.FINAL_MERGE,
				target.repositoryId(), JobSetupOperation.WORKFLOW, inputs, Set.of(intermediary.id()), null));
		if (!merges().dispatched(merge, dispatched.correlation(), branch)) {
			// The Final Merge ended meanwhile: its run is given up, so it never exchanges.
			runs().close(dispatched.correlation());
			return new Outcome.Obsolete("NOT_CONFIRMED");
		}
		return new Outcome.Deferred(this.clock.instant().plus(RUN_POLL));
	}

	/** Steps 4 and 5: the run read, and once it succeeded, the branch read back at the merge commit. */
	private Outcome poll(FinalMerge merge, String head) {
		UUID correlationId = Objects.requireNonNull(merge.correlationId());
		Outcome refused = exchangeRefused(merge, correlationId);
		if (refused != null) {
			return refused;
		}
		Run run = runs().run(correlationId).orElse(null);
		Instant now = this.clock.instant();
		Instant dispatchedAt = Objects.requireNonNull(merge.dispatchedAt());
		Instant timeout = dispatchedAt.plus(ActionsRuns.RUN_TIMEOUT);
		if (run == null || run.status() != RunStatus.COMPLETED) {
			boolean late = !now.isBefore(timeout);
			merges().runRead(merge, run == null ? null : run.status().name(), late ? TIMED_OUT : null);
			if (late) {
				log.warn("Final Merge {}: the run under correlation {} did not complete within {}; it is given up",
						merge.authorizationId(), correlationId, ActionsRuns.RUN_TIMEOUT);
				// Closed and cancelled, so it never trades for a token beside the run that replaces it.
				runs().close(correlationId);
				return new Outcome.Transient("RUN_FAILED");
			}
			Instant next = now.plus(now.isBefore(dispatchedAt.plus(RUN_POLL_FAST)) ? RUN_POLL : RUN_POLL_LATER);
			return new Outcome.Deferred(next.isAfter(timeout) ? timeout : next);
		}
		String conclusion = run.conclusion() == null ? "unknown" : run.conclusion();
		merges().runRead(merge, RunStatus.COMPLETED.name(), conclusion);
		if (run.marker() == RunMarker.ACTIONS_QUOTA) {
			return end(merge, "ACTIONS_QUOTA");
		}
		if (!"success".equals(conclusion)) {
			// A refusal that landed after the first read failed the run: it decides, not the conclusion.
			Outcome late = exchangeRefused(merge, correlationId);
			if (late != null) {
				return late;
			}
			log.warn("Final Merge {}: the run {} concluded {}", merge.authorizationId(), run.id(), conclusion);
			return new Outcome.Transient("RUN_FAILED");
		}
		String branch = Objects.requireNonNullElseGet(merge.branch(), () -> FinalMerges.branch(merge.jobId()));
		SourceBranch pushed = sources().branch(merge.productId(), branch);
		if (pushed.block() != null) {
			return new Outcome.Deferred(this.clock.instant().plus(FinalMergeCheckOperation.CUSTOMER_WAIT));
		}
		if (!head.equals(pushed.sha())) {
			// The run is spent: the next attempt reads the branch again, and the attempts running out end it RUN_FAILED.
			log.warn("Final Merge {}: {} reads back {} instead of the merge commit", merge.authorizationId(), branch,
					pushed.sha() == null ? "none" : pushed.sha());
			merges().runRead(merge, RunStatus.COMPLETED.name(), NOT_READ_BACK);
			return new Outcome.Transient("RUN_FAILED");
		}
		return ready(merge, branch);
	}

	/** The resolution: J9 applies, with the Setup's own effects, and both sides get the branch to merge on GitHub. */
	private Outcome ready(FinalMerge merge, String branch) {
		if (!merges().branchReady(merge.authorizationId(), Objects.requireNonNull(merge.operationKey()), branch)) {
			// The row left CONFIRMED or RUNNING meanwhile, so nothing applied J9: the marker's own release decides.
			return new Outcome.Obsolete("NOT_CONFIRMED");
		}
		return new Outcome.Succeeded(Map.of("branch", branch));
	}

	/**
	 * The exchange's refusal of the run, read now: the Final Merge ends {@code EXCHANGE_REFUSED}, since a run without
	 * a token pushes nothing and a second run would be refused the same way.
	 * @return what the refusal decides, or none while the correlation is not refused
	 */
	private @Nullable Outcome exchangeRefused(FinalMerge merge, UUID correlationId) {
		Correlation correlation = runs().correlation(correlationId).orElse(null);
		if (correlation == null || correlation.status() != ActionsRuns.Status.REFUSED) {
			return null;
		}
		log.warn("Final Merge {}: the exchange refused correlation {}: {}", merge.authorizationId(), correlationId,
				correlation.refusal() == null ? "unknown" : correlation.refusal().code());
		return end(merge, "EXCHANGE_REFUSED");
	}

	/**
	 * The Final Merge ends with its named reason: the marker goes, and the Job follows the closure fallback. A run
	 * still out is given up first, closed and cancelled, so it never trades its ID token for one that would push
	 * {@code vibefix/final-{jobId}} into the Customer's repository after the Job took the fallback.
	 */
	private Outcome end(FinalMerge merge, String refusal) {
		UUID correlation = merge.correlationId();
		if (correlation != null && !merge.runSpent()) {
			runs().close(correlation);
		}
		merges().fallback(merge.authorizationId(), "REFUSED", refusal);
		return new Outcome.Aborted(refusal);
	}

	private FinalMerges merges() {
		return this.mergesProvider.getObject();
	}

	private FinalMergePreconditions preconditions() {
		return this.preconditionsProvider.getObject();
	}

	private ProductSources sources() {
		return this.sourcesProvider.getObject();
	}

	private PlatformRepositories repositories() {
		return this.repositoriesProvider.getObject();
	}

	private ActionsRuns runs() {
		return this.runsProvider.getObject();
	}

	private JobBusy busy() {
		return this.busyProvider.getObject();
	}

}
