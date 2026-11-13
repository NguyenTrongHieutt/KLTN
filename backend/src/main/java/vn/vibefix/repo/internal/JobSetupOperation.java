package vn.vibefix.repo.internal;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
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
import vn.vibefix.github.ExchangeReason;
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
import vn.vibefix.product.ProductSources.Head;
import vn.vibefix.product.ProductSources.SourceCheck;
import vn.vibefix.repo.internal.JobSetups.Block;
import vn.vibefix.repo.internal.JobSetups.Setup;

/**
 * {@value JobSetups#OPERATION} (FR-18, AD-5, AD-11, AD-28, AD-44, AD-46, R4, Story 23.6b): Setup's two stages as External
 * Operations of one type in the GitHub-long lane, each attempt resuming at its stage's first unfinished step.
 * <ol>
 * <li>The waiting stage, keyed by the attempt, with no busy marker: the Source Repository checked again through the
 * Customer's Source App (the binding, the admin right, {@code vibefix.yml}); a block stops the start. Then its default
 * branch's head is read and recorded, and the Customer is asked to self-confirm it (N-32). vibefix scans no code.</li>
 * <li>The copy stage, keyed by the Customer's confirmation, under the marker:
 * <ol>
 * <li>the Source Repository checked again;</li>
 * <li>the gate: the organization, the runner, and the installation read back (AD-12, AD-46); a difference waits
 * {@link #FROZEN_WAIT} and looks again;</li>
 * <li>the private Intermediary and Working Repositories created or found, and their settings read back (private,
 * features and forking off, Actions disabled); the copy pushes without forcing, so what an earlier attempt pushed, an
 * ancestor of the confirmed head, is kept;</li>
 * <li>the head read again: one that moved since the confirmation asks the Customer again, with nothing dispatched;</li>
 * <li>{@code copy} of {@code vibefix.yml} dispatched in the Customer's Source Repository through their Source App,
 * with the confirmed head and a grant of the two new repositories; the exchange correlation is recorded;</li>
 * <li>the run polled every {@link #RUN_POLL} for its first {@link #RUN_POLL_FAST}, then every {@link #RUN_POLL_LATER},
 * while it is queued or in progress, up to {@link ActionsRuns#RUN_TIMEOUT}, when its correlation is closed and it is
 * cancelled;</li>
 * <li>once it succeeded, both repositories' default branch read back through the Platform App at exactly the
 * confirmed head; then the Invitations stage starts (Story 7.3).</li>
 * </ol>
 * </li>
 * </ol>
 * The run's outcomes: the exchange refusing {@code sha} (the head moved after the confirmation) sends the Setup back to
 * waiting with the new head; any other refusal blocks it {@code EXCHANGE_REFUSED}; a run Actions' quota stopped blocks it
 * {@code ACTIONS_QUOTA} at once; any other conclusion, the timeout, or a success that does not read back at the
 * confirmed head is {@code RUN_FAILED}, which the lane policy dispatches again under a new correlation until its attempts
 * run out, and then blocks the Setup; an Administrator's retry from ADM-7 (FR-64) copies again. During Setup, no code
 * reaches a repository whose settings were not read back, and nothing is dispatched without a recorded confirmation
 * at the current head. A GitHub failure is retried by the lane policy, and the gate's own reads wait like a
 * difference; a frozen Job only waits (AD-28). The marker is released by every resolution.
 */
@Component
class JobSetupOperation implements OperationHandler {

	static final OperationPolicy POLICY = new OperationPolicy(6, Duration.ofMinutes(1));

	/** How long a frozen Job's Setup waits before it looks again (AD-28). */
	static final Duration FROZEN_WAIT = Duration.ofMinutes(15);

	/** How often a dispatched copy run is read while it is queued or in progress, at first (AD-46). */
	static final Duration RUN_POLL = Duration.ofSeconds(10);

	/** How long after its dispatch a copy run is read every {@link #RUN_POLL}. */
	static final Duration RUN_POLL_FAST = Duration.ofMinutes(2);

	/** How often it is read after that, until {@link ActionsRuns#RUN_TIMEOUT}. */
	static final Duration RUN_POLL_LATER = Duration.ofSeconds(30);

	/** The published workflow the copy runs, in the Customer's {@code .github/workflows/} (AD-45). */
	static final String WORKFLOW = "vibefix.yml";

	/** What a run is called once vibefix stopped waiting for it after {@link ActionsRuns#RUN_TIMEOUT}. */
	static final String TIMED_OUT = "timed_out";

	/** What a successful run is called when its copy does not read back at the confirmed head: it is dispatched again. */
	static final String NOT_READ_BACK = "not_read_back";

	private static final Logger log = LoggerFactory.getLogger(JobSetupOperation.class);

	/** Looked up on use: {@code operations} collects its handlers before the modules they call exist. */
	private final ObjectProvider<JobSetups> setupsProvider;

	private final ObjectProvider<JobBusy> busyProvider;

	private final ObjectProvider<ProductSources> sourcesProvider;

	private final ObjectProvider<PlatformRepositories> repositoriesProvider;

	private final ObjectProvider<ActionsRuns> runsProvider;

	private final Clock clock;

	JobSetupOperation(ObjectProvider<JobSetups> setups, ObjectProvider<JobBusy> busy, ObjectProvider<ProductSources> sources,
			ObjectProvider<PlatformRepositories> repositories, ObjectProvider<ActionsRuns> runs, Clock clock) {
		this.setupsProvider = setups;
		this.busyProvider = busy;
		this.sourcesProvider = sources;
		this.repositoriesProvider = repositories;
		this.runsProvider = runs;
		this.clock = clock;
	}

	@Override
	public String type() {
		return JobSetups.OPERATION;
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
		UUID id = UUID.fromString(String.valueOf(run.payload().get("setupId")));
		Object named = run.payload().get("confirmationId");
		UUID confirmation = named == null ? null : UUID.fromString(String.valueOf(named));
		Setup setup = setups().find(id).orElse(null);
		if (setup == null) {
			return new Outcome.Obsolete("SETUP_GONE");
		}
		switch (setup.status()) {
			case "DONE" -> {
				return Outcome.succeeded();
			}
			case "BLOCKED" -> {
				// FR-64: an Administrator retried the copy whose runs kept failing, and it copies again under its marker.
				if (setup.block() != Block.RUN_FAILED || confirmation == null || !confirmation.equals(setup.confirmationId())) {
					return new Outcome.Obsolete("SETUP_BLOCKED");
				}
				if (!setups().latest(setup.jobId()).map(Setup::id).filter(setup.id()::equals).isPresent()) {
					return new Outcome.Obsolete("SETUP_REPLACED");
				}
				if (!setups().resume(setup)) {
					return new Outcome.Obsolete("JOB_NOT_STARTING");
				}
				setup = setups().find(id).orElseThrow();
			}
			case "ENDED" -> {
				return new Outcome.Obsolete("SETUP_ENDED");
			}
			case "FAILED" -> {
				// Only the Job's latest attempt runs again: an older one (Story 23.1's migration left some FAILED
				// beside the attempt that replaced them) would copy into the Job's repositories a second time.
				if (!setups().latest(setup.jobId()).map(Setup::id).filter(setup.id()::equals).isPresent()) {
					return new Outcome.Obsolete("SETUP_REPLACED");
				}
				// The stage that failed is the one that runs again: the copy for its own confirmation only.
				if (!Objects.equals(confirmation, setup.confirmationId())) {
					return new Outcome.Obsolete("CONFIRMATION_REPLACED");
				}
				if (!setups().resume(setup)) {
					return new Outcome.Obsolete("JOB_NOT_STARTING");
				}
				setup = setups().find(id).orElseThrow();
			}
			default -> {
			}
		}
		if (confirmation == null) {
			if (!"CONFIRMING".equals(setup.status()) || setup.checkedAt() != null) {
				// The head was read: the attempt waits for the Customer, or has moved on.
				return Outcome.succeeded();
			}
		}
		else if (!"RUNNING".equals(setup.status())) {
			// The copy waits for a self-confirmation at the current head (R4).
			return new Outcome.Obsolete(RepoReason.SELF_CONFIRMATION_MISSING.name());
		}
		else if (!confirmation.equals(setup.confirmationId())) {
			return new Outcome.Obsolete("CONFIRMATION_REPLACED");
		}
		BusyJob job = busy().job(setup.jobId()).orElse(null);
		if (job == null || !"STARTING".equals(job.state())) {
			return new Outcome.Obsolete("JOB_NOT_STARTING");
		}
		if (job.frozen()) {
			return new Outcome.Deferred(this.clock.instant().plus(FROZEN_WAIT));
		}
		try {
			if (confirmation == null) {
				return await(setup);
			}
			if (job.busy().filter(marker -> marker.operationKey().equals(run.key())).isEmpty()) {
				return new Outcome.Obsolete("NOT_RESERVED");
			}
			return copy(setup);
		}
		catch (WorkflowRefused refused) {
			// The workflow file changed since the check: nothing was recorded or dispatched.
			Block block = Block.valueOf(refused.refusal().name());
			setups().block(setup, block);
			return new Outcome.Aborted(block.name());
		}
		catch (GitHubException failed) {
			// The message names the step and GitHub's status, never a token.
			log.warn("Setup {} of Job {}: {}", setup.id(), setup.jobId(), failed.getMessage());
			return new Outcome.Transient(failed.status() == 0 ? "GITHUB_UNAVAILABLE" : "GITHUB_HTTP_" + failed.status());
		}
	}

	/**
	 * The waiting stage: the Source Repository checked again, then its default branch's head read and recorded, and the
	 * Customer asked to confirm it. A GitHub failure is a {@link GitHubException}, which the lane retries.
	 */
	private Outcome await(Setup setup) {
		Outcome blocked = check(setup);
		if (blocked != null) {
			return blocked;
		}
		Head head = sources().head(setup.productId());
		if (head.block() != null) {
			return block(setup, Block.valueOf(head.block().name()));
		}
		setups().checked(setup, head.fullName(), head.branch(), Objects.requireNonNull(head.sha()));
		return Outcome.succeeded();
	}

	/** The Source checks through the Source App (AD-13, AD-45): the block that ends this attempt, or none. */
	private @Nullable Outcome check(Setup setup) {
		SourceCheck source = sources().check(setup.productId());
		return source.block() == null ? null : block(setup, Block.valueOf(source.block().name()));
	}

	private Outcome block(Setup setup, Block block) {
		setups().block(setup, block);
		return new Outcome.Aborted(block.name());
	}

	/** The copy stage: a run to dispatch when none is out, else the one out polled. */
	private Outcome copy(Setup setup) {
		if (setup.correlationId() == null || setup.runSpent()) {
			return dispatch(setup);
		}
		return poll(setup);
	}

	/**
	 * Steps 1 to 5: checked again, the gate, both repositories created or found and read back, the head read again, then
	 * the copy dispatched through the Customer's Source App with the confirmed head and a grant of the two repositories.
	 */
	private Outcome dispatch(Setup setup) {
		Outcome blocked = check(setup);
		if (blocked != null) {
			return blocked;
		}
		Outcome waiting = gate(setup);
		if (waiting != null) {
			return waiting;
		}
		PlatformRepository intermediary = setup.intermediary();
		if (intermediary == null) {
			String name = JobSetups.intermediaryName(setup.jobId());
			intermediary = repositories().find(name).orElseGet(() -> repositories().create(name));
			setups().intermediaryCreated(setup, intermediary);
		}
		if (setup.intermediaryAt() == null) {
			Outcome differs = readBack(setup, intermediary);
			if (differs != null) {
				return differs;
			}
			if (!setups().intermediaryReadBack(setup)) {
				return new Outcome.Obsolete("SETUP_NOT_RUNNING");
			}
		}
		PlatformRepository working = setup.working();
		if (working == null) {
			String name = JobSetups.workingName(setup.jobId());
			working = repositories().find(name).orElseGet(() -> repositories().create(name));
			setups().workingCreated(setup, working);
		}
		if (setup.workingAt() == null) {
			Outcome differs = readBack(setup, working);
			if (differs != null) {
				return differs;
			}
			if (!setups().workingReadBack(setup)) {
				return new Outcome.Obsolete("SETUP_NOT_RUNNING");
			}
		}
		DispatchTarget target = sources().dispatchTarget(setup.productId());
		if (target.block() != null) {
			return block(setup, Block.valueOf(target.block().name()));
		}
		String approved = Objects.requireNonNull(setup.approvedSha());
		// The head read just before the dispatch: one that moved since the confirmation is never copied.
		Head head = sources().head(setup.productId());
		if (head.block() != null) {
			return block(setup, Block.valueOf(head.block().name()));
		}
		if (!approved.equals(head.sha())) {
			log.info("Setup {}: the head moved from the confirmed {} to {} before the copy", setup.id(), approved, head.sha());
			return headMoved(setup, head);
		}
		Map<String, String> inputs = new LinkedHashMap<>();
		inputs.put("operation", Operation.COPY.code());
		inputs.put("sha", approved);
		inputs.put("branch", Objects.requireNonNull(setup.defaultBranch()));
		inputs.put("intermediary", intermediary.fullName());
		inputs.put("working", working.fullName());
		Dispatched dispatched = runs().dispatch(new Dispatch(App.SOURCE, target.installationId(), Operation.COPY,
				target.repositoryId(), WORKFLOW, inputs, Set.of(intermediary.id(), working.id()), approved));
		if (!setups().dispatched(setup, dispatched.correlation())) {
			// The attempt moved on meanwhile: its run is given up, so it never exchanges.
			runs().close(dispatched.correlation());
			return new Outcome.Obsolete("SETUP_NOT_RUNNING");
		}
		return new Outcome.Deferred(this.clock.instant().plus(RUN_POLL));
	}

	/** Steps 6 and 7: the run read, and once it succeeded, both repositories read back at the confirmed head. */
	private Outcome poll(Setup setup) {
		UUID correlationId = Objects.requireNonNull(setup.correlationId());
		Outcome refused = refused(setup, correlationId);
		if (refused != null) {
			return refused;
		}
		Run run = runs().run(correlationId).orElse(null);
		Instant now = this.clock.instant();
		Instant dispatchedAt = Objects.requireNonNull(setup.dispatchedAt());
		Instant timeout = dispatchedAt.plus(ActionsRuns.RUN_TIMEOUT);
		if (run == null || run.status() != RunStatus.COMPLETED) {
			boolean late = !now.isBefore(timeout);
			setups().runRead(setup, run == null ? null : run.status().name(), late ? TIMED_OUT : null);
			if (late) {
				log.warn("Setup {}: the copy run under correlation {} did not complete within {}; it is given up",
						setup.id(), correlationId, ActionsRuns.RUN_TIMEOUT);
				// Closed and cancelled, so it never trades for a token beside the run that replaces it.
				runs().close(correlationId);
				return new Outcome.Transient(Block.RUN_FAILED.name());
			}
			Instant next = now.plus(now.isBefore(dispatchedAt.plus(RUN_POLL_FAST)) ? RUN_POLL : RUN_POLL_LATER);
			return new Outcome.Deferred(next.isAfter(timeout) ? timeout : next);
		}
		String conclusion = run.conclusion() == null ? "unknown" : run.conclusion();
		setups().runRead(setup, RunStatus.COMPLETED.name(), conclusion);
		if (run.marker() == RunMarker.ACTIONS_QUOTA) {
			return block(setup, Block.ACTIONS_QUOTA);
		}
		if (!"success".equals(conclusion)) {
			// A refusal that landed after the first read failed the run: it decides, not the conclusion.
			Outcome late = refused(setup, correlationId);
			if (late != null) {
				return late;
			}
			log.warn("Setup {}: the copy run {} concluded {}", setup.id(), run.id(), conclusion);
			return new Outcome.Transient(Block.RUN_FAILED.name());
		}
		PlatformRepository intermediary = Objects.requireNonNull(setup.intermediary());
		PlatformRepository working = Objects.requireNonNull(setup.working());
		String approved = Objects.requireNonNull(setup.approvedSha());
		String branch = Objects.requireNonNull(setup.defaultBranch());
		for (PlatformRepository repository : List.of(intermediary, working)) {
			String head;
			try {
				head = repositories().branchHead(repository, branch);
			}
			catch (GitHubException missing) {
				if (missing.status() != 404) {
					throw missing;
				}
				head = "none";
			}
			if (!approved.equals(head)) {
				// The run is spent: the next attempt dispatches again, and the attempts running out block RUN_FAILED.
				log.warn("Setup {}: {} reads back {} on {} instead of the confirmed head {}", setup.id(), repository.fullName(),
						head, branch, approved);
				setups().runRead(setup, RunStatus.COMPLETED.name(), NOT_READ_BACK);
				return new Outcome.Transient(Block.RUN_FAILED.name());
			}
		}
		setups().done(setup, working);
		return new Outcome.Succeeded(Map.of("intermediary", intermediary.fullName(), "working", working.fullName()));
	}

	/**
	 * The exchange's refusal of the run, read now: {@code sha} (the head moved after the confirmation) asks the Customer
	 * again, and any other blocks the start {@code EXCHANGE_REFUSED}.
	 * @return what the refusal decides, or none while the correlation is not refused
	 */
	private @Nullable Outcome refused(Setup setup, UUID correlationId) {
		Correlation correlation = runs().correlation(correlationId).orElse(null);
		if (correlation == null || correlation.status() != ActionsRuns.Status.REFUSED) {
			return null;
		}
		ExchangeReason refusal = correlation.refusal();
		if (refusal == ExchangeReason.SHA) {
			Head head = sources().head(setup.productId());
			if (head.block() != null) {
				return block(setup, Block.valueOf(head.block().name()));
			}
			return headMoved(setup, head);
		}
		setups().block(setup, Block.EXCHANGE_REFUSED, refusal == null ? "unknown" : refusal.code());
		return new Outcome.Aborted(Block.EXCHANGE_REFUSED.name());
	}

	/** The head moved after the confirmation: the Customer confirms the one read now, and nothing more is copied. */
	private Outcome headMoved(Setup setup, Head head) {
		setups().headMoved(setup, head.fullName(), head.branch(), Objects.requireNonNull(head.sha()));
		return new Outcome.Obsolete("HEAD_MOVED");
	}

	/**
	 * The gate, before anything is created: the organization, the runner, and the installation read back
	 * (AD-12, AD-46). A runner, Actions, 2FA, or repository-selection difference holds new setups until it is
	 * fixed; the attempt waits and looks again.
	 * @return the wait, or {@code null} when everything is as AD-10, AD-11, and AD-46 expect
	 */
	private @Nullable Outcome gate(Setup setup) {
		List<String> differences;
		try {
			differences = new ArrayList<>(repositories().organizationDifferences());
			differences.addAll(repositories().runnerDifferences());
			differences.addAll(repositories().installationDifferences());
		}
		catch (GitHubException failed) {
			// An unreadable organization waits like a difference, without using up the attempts.
			log.warn("Setup {} waits: the organization was not read back: {}", setup.id(), failed.getMessage());
			return new Outcome.Deferred(this.clock.instant().plus(FROZEN_WAIT));
		}
		if (differences.isEmpty()) {
			return null;
		}
		log.warn("Setup {} waits: the organization differs from AD-10, AD-11 or AD-46: {}", setup.id(), differences);
		return new Outcome.Deferred(this.clock.instant().plus(FROZEN_WAIT));
	}

	/**
	 * AD-11's settings read back before anything is dispatched into the repository: private, the features and forking
	 * off, and Actions disabled. A difference is retried by the lane policy, and nothing is dispatched meanwhile.
	 */
	private @Nullable Outcome readBack(Setup setup, PlatformRepository repository) {
		List<String> differences = repositories().settingsDifferences(repository);
		if (differences.isEmpty()) {
			return null;
		}
		log.warn("Setup {} waits: {} differs from AD-11: {}", setup.id(), repository.fullName(), differences);
		return new Outcome.Transient("REPOSITORY_SETTINGS_DIFFER");
	}

	private JobSetups setups() {
		return this.setupsProvider.getObject();
	}

	private JobBusy busy() {
		return this.busyProvider.getObject();
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

}
