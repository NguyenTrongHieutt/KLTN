package vn.vibefix.repo.internal;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
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
import vn.vibefix.operations.OperationPolicy;
import vn.vibefix.operations.OperationRun;
import vn.vibefix.operations.Outcome;
import vn.vibefix.product.ProductSources;
import vn.vibefix.product.ProductSources.SourceCheck;
import vn.vibefix.repo.internal.JobSetups.Block;
import vn.vibefix.repo.internal.JobSetups.Setup;

/**
 * {@value JobSetups#OPERATION} (FR-18, AD-5, AD-11, AD-28): Setup as a precondition External Operation in
 * the GitHub-long lane. Each attempt resumes at the first unfinished step, and each step finds what an
 * earlier attempt created before it creates anything:
 * <ol>
 * <li>"Kiểm tra lại dự án gốc": the Source Repository read again with the Customer's token (AD-13); a check
 * that fails blocks the start. The default branch's head, read through the GitHub API, becomes the approved
 * commit (vibefix scans no code; Story 23.6 replaces it with the Customer's self-confirmed SHA).</li>
 * <li>"Tạo kho trung gian": the public Intermediary Repository, then the copy of exactly the approved
 * commit in the sandbox.</li>
 * <li>"Tạo kho làm việc": the fork inside the organization with its features off, the organization read
 * back, and AD-11's rulesets created and read back.</li>
 * </ol>
 * A GitHub failure is retried by the lane policy; a frozen Job only waits (AD-28). The marker is released
 * by every resolution.
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
			if (setup.intermediaryAt() == null) {
				copy(setup, job);
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

	/** Step 2: the Intermediary Repository at exactly the approved commit. */
	private void copy(Setup setup, BusyJob job) {
		PlatformRepository intermediary = setup.intermediary();
		if (intermediary == null) {
			String name = JobSetups.intermediaryName(job.jobId());
			intermediary = repositories().find(name).orElseGet(() -> repositories().create(name));
			setups().intermediaryCreated(setup, intermediary);
		}
		repositories().copy(setup.sourceFullName(), setup.approvedSha(), setup.defaultBranch(), intermediary);
		setups().copied(setup);
	}

	/** Step 3: the Working Repository, the organization's settings, and the rulesets, each read back. */
	private Outcome work(Setup setup, BusyJob job) {
		PlatformRepository intermediary = setup.intermediary();
		String name = JobSetups.workingName(job.jobId());
		PlatformRepository working = repositories().find(name)
			.orElseGet(() -> repositories().fork(intermediary, name));
		setups().workingCreated(setup, working);
		repositories().turnFeaturesOff(working);
		List<String> organization = new java.util.ArrayList<>(repositories().organizationDifferences());
		organization.addAll(repositories().installationDifferences());
		if (!organization.isEmpty()) {
			// AD-12: an Actions, 2FA, or repository-selection difference blocks new setups until it is fixed.
			log.warn("Setup {} waits: the organization differs from AD-10/AD-11: {}", setup.id(), organization);
			return new Outcome.Deferred(this.clock.instant().plus(FROZEN_WAIT));
		}
		repositories().createRulesets(intermediary);
		List<String> rulesets = repositories().rulesetDifferences(intermediary);
		if (!rulesets.isEmpty()) {
			log.warn("Setup {} waits: the rulesets differ from AD-11: {}", setup.id(), rulesets);
			return new Outcome.Transient("RULESETS_DIFFER");
		}
		setups().done(setup, working);
		return new Outcome.Succeeded(Map.of("intermediary", intermediary.fullName(), "working", working.fullName()));
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
