package vn.vibefix.repo.internal;

import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import org.jspecify.annotations.Nullable;
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
import vn.vibefix.operations.OperationPolicy;
import vn.vibefix.operations.OperationRun;
import vn.vibefix.operations.Outcome;
import vn.vibefix.product.ProductSources;
import vn.vibefix.product.ProductSources.SourceCheck;
import vn.vibefix.repo.internal.JobSetups.Block;
import vn.vibefix.repo.internal.JobSetups.Setup;

/**
 * {@value JobSetups#OPERATION} (FR-18, AD-5, AD-11, AD-28, D1, D5): Setup as a precondition External
 * Operation in the GitHub-long lane. Each attempt resumes at the first unfinished step, and each step finds
 * what an earlier attempt created before it creates anything:
 * <ol>
 * <li>"Kiểm tra lại dự án gốc": the Source Repository read again with the Customer's token (AD-13); a check
 * that fails blocks the start. The default branch's head, read through the GitHub API, becomes the approved
 * commit (vibefix scans no code; Story 23.6 replaces it with the Customer's self-confirmed SHA).</li>
 * <li>Before anything is created, the gate: the organization, the runner, and the installation read back
 * (AD-12, AD-46). A difference waits {@link #FROZEN_WAIT} and looks again.</li>
 * <li>"Tạo kho trung gian": the private Intermediary Repository, its settings read back (private, features
 * and forking off, Actions disabled), then the copy of exactly the approved commit in the sandbox.</li>
 * <li>"Tạo kho làm việc": the private Working Repository, a second copy (no fork, no ruleset), read back the
 * same way, then the approved commit pushed into it from the Intermediary.</li>
 * </ol>
 * During Setup, no code is pushed into a repository whose settings were not read back. A repeated push of the
 * approved commit is a no-op, so a retried attempt resumes safely. A GitHub failure is retried by the lane
 * policy, and the gate's own reads wait like a difference; a frozen Job only waits (AD-28). The marker is
 * released by every resolution.
 */
@Component
class JobSetupOperation implements OperationHandler {

	static final OperationPolicy POLICY = new OperationPolicy(6, Duration.ofMinutes(1));

	/** How long a frozen Job's Setup waits before it looks again (AD-28). */
	static final Duration FROZEN_WAIT = Duration.ofMinutes(15);

	private static final Logger log = LoggerFactory.getLogger(JobSetupOperation.class);

	/** Looked up on use: {@code operations} collects its handlers before the modules they call exist. */
	private final ObjectProvider<JobSetups> setupsProvider;

	private final ObjectProvider<JobBusy> busyProvider;

	private final ObjectProvider<ProductSources> sourcesProvider;

	private final ObjectProvider<PlatformRepositories> repositoriesProvider;

	private final Clock clock;

	JobSetupOperation(ObjectProvider<JobSetups> setups, ObjectProvider<JobBusy> busy, ObjectProvider<ProductSources> sources,
			ObjectProvider<PlatformRepositories> repositories, Clock clock) {
		this.setupsProvider = setups;
		this.busyProvider = busy;
		this.sourcesProvider = sources;
		this.repositoriesProvider = repositories;
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
		Setup setup = setups().find(id).orElse(null);
		if (setup == null) {
			return new Outcome.Obsolete("SETUP_GONE");
		}
		switch (setup.status()) {
			case "DONE" -> {
				return Outcome.succeeded();
			}
			case "BLOCKED" -> {
				return new Outcome.Obsolete("SETUP_BLOCKED");
			}
			case "FAILED" -> {
				// Only the Job's latest attempt runs again: an older one (Story 23.1's migration left some FAILED
				// beside the attempt that replaced them) would copy into the Job's repositories a second time.
				if (!setups().latest(setup.jobId()).map(Setup::id).filter(setup.id()::equals).isPresent()) {
					return new Outcome.Obsolete("SETUP_REPLACED");
				}
				if (!setups().resume(setup)) {
					return new Outcome.Obsolete("JOB_NOT_STARTING");
				}
				setup = setups().find(id).orElseThrow();
			}
			default -> {
			}
		}
		BusyJob job = busy().job(setup.jobId()).orElse(null);
		if (job == null || !"STARTING".equals(job.state())) {
			return new Outcome.Obsolete("JOB_NOT_STARTING");
		}
		if (job.frozen()) {
			return new Outcome.Deferred(this.clock.instant().plus(FROZEN_WAIT));
		}
		try {
			if (setup.checkedAt() == null) {
				Outcome blocked = check(setup);
				if (blocked != null) {
					return blocked;
				}
				setup = setups().find(id).orElseThrow();
			}
			Outcome waiting = gate(setup);
			if (waiting != null) {
				return waiting;
			}
			if (setup.intermediaryAt() == null) {
				Outcome differs = copy(setup, job);
				if (differs != null) {
					return differs;
				}
				setup = setups().find(id).orElseThrow();
			}
			return work(setup, job);
		}
		catch (GitHubException failed) {
			// The message names the step and GitHub's status, never a token.
			log.warn("Setup {} of Job {}: {}", setup.id(), setup.jobId(), failed.getMessage());
			return new Outcome.Transient(failed.status() == 0 ? "GITHUB_UNAVAILABLE" : "GITHUB_HTTP_" + failed.status());
		}
	}

	/**
	 * Step 1: the Source Repository checked again, then its default branch's head read through the GitHub
	 * API and recorded as the approved commit. A GitHub failure is a {@link GitHubException}, which the
	 * lane retries.
	 * @return the block that ends this attempt, or {@code null} once the step is done
	 */
	private Outcome check(Setup setup) {
		SourceCheck source = sources().check(setup.productId());
		if (source.block() != null) {
			Block block = Block.valueOf(source.block().name());
			setups().block(setup, block);
			return new Outcome.Aborted(block.name());
		}
		String head = repositories().branchHead(source.repositoryFullName(), source.defaultBranch());
		setups().checked(setup, source.repositoryFullName(), source.defaultBranch(), head);
		return null;
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
	 * Step 2: the private Intermediary Repository, read back, at exactly the approved commit.
	 * @return the retry when its settings differ, or {@code null} once the step is done
	 */
	private @Nullable Outcome copy(Setup setup, BusyJob job) {
		PlatformRepository intermediary = setup.intermediary();
		if (intermediary == null) {
			String name = JobSetups.intermediaryName(job.jobId());
			intermediary = repositories().find(name).orElseGet(() -> repositories().create(name));
			setups().intermediaryCreated(setup, intermediary);
		}
		Outcome differs = readBack(setup, intermediary);
		if (differs != null) {
			return differs;
		}
		repositories().copy(setup.sourceFullName(), setup.approvedSha(), setup.defaultBranch(), intermediary);
		setups().copied(setup);
		return null;
	}

	/**
	 * Step 3: the private Working Repository, read back, then a second copy of the approved commit taken from
	 * the Intermediary (a read token narrowed to it), never from the Source Repository, which may have moved or
	 * gone private since step 2.
	 */
	private Outcome work(Setup setup, BusyJob job) {
		PlatformRepository intermediary = Objects.requireNonNull(setup.intermediary());
		PlatformRepository working = setup.working();
		if (working == null) {
			String name = JobSetups.workingName(job.jobId());
			working = repositories().find(name).orElseGet(() -> repositories().create(name));
			setups().workingCreated(setup, working);
		}
		Outcome differs = readBack(setup, working);
		if (differs != null) {
			return differs;
		}
		repositories().copy(intermediary, setup.approvedSha(), setup.defaultBranch(), setup.defaultBranch(), working);
		setups().done(setup, working);
		return new Outcome.Succeeded(Map.of("intermediary", intermediary.fullName(), "working", working.fullName()));
	}

	/**
	 * AD-11's settings read back before any code is pushed: private, the features and forking off, and Actions
	 * disabled. A difference is retried by the lane policy, and nothing is pushed meanwhile.
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

}
