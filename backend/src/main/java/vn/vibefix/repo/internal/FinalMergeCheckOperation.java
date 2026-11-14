package vn.vibefix.repo.internal;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.SortedMap;
import java.util.TreeSet;
import java.util.UUID;

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
import vn.vibefix.product.ProductSources;
import vn.vibefix.product.ProductSources.SourceWorkflows;
import vn.vibefix.repo.internal.FinalMergePreconditions.Read;
import vn.vibefix.repo.internal.FinalMerges.FinalMerge;
import vn.vibefix.scanning.SensitiveChanges;
import vn.vibefix.scanning.SensitiveChanges.SensitiveChange;

/**
 * {@value FinalMerges#CHECK} (FR-37, AD-11, AD-45, SG-19, R1, Story 23.9a): before the Customer confirms, the
 * preconditions, the whole Job's Sensitive Changes (from the J7 commit to the Intermediary head), and the
 * {@code .github/workflows/} trees of the Source Repository's default branch and of the Intermediary's merge commit.
 * A clean check is {@code READY} for N-18; a difference under {@code .github/workflows/} is recorded as the Final
 * Merge's blocking workflow list, which the closure section shows as N-18's workflow-file state, because a run's
 * {@code GITHUB_TOKEN} can never push those files (deferred-work 626). Story 23.9a deleted the trial merge: vibefix
 * never merges into a Customer repository. A failed guard hands the Job to the closure fallback with nothing pushed; a
 * Customer to wait for, an open grant, or a frozen Job defers. Collaborators are looked up on use.
 */
@Component
class FinalMergeCheckOperation implements OperationHandler {

	static final Duration CUSTOMER_WAIT = Duration.ofMinutes(30);

	static final Duration SHORT_WAIT = Duration.ofMinutes(10);

	private final ObjectProvider<FinalMerges> merges;

	private final ObjectProvider<FinalMergePreconditions> preconditions;

	private final ObjectProvider<ProductSources> sources;

	private final ObjectProvider<PlatformRepositories> repositories;

	private final ObjectProvider<SensitiveChanges> sensitive;

	private final ObjectProvider<JobBusy> busy;

	private final Clock clock;

	FinalMergeCheckOperation(ObjectProvider<FinalMerges> merges, ObjectProvider<FinalMergePreconditions> preconditions,
			ObjectProvider<ProductSources> sources, ObjectProvider<PlatformRepositories> repositories,
			ObjectProvider<SensitiveChanges> sensitive, ObjectProvider<JobBusy> busy, Clock clock) {
		this.merges = merges;
		this.preconditions = preconditions;
		this.sources = sources;
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
			PlatformRepository intermediary = Objects.requireNonNull(read.intermediary());
			String approved = Objects.requireNonNull(setup.approvedSha());
			List<SensitiveChange> changes = head.equals(approved) ? List.of()
					: this.sensitive.getObject()
						.classify(this.repositories.getObject().compare(intermediary, approved, head).files());
			List<String> workflows = workflowFiles(this.sources.getObject(), this.repositories.getObject(),
					merge.productId(), intermediary, head).orElse(null);
			if (workflows == null) {
				// The Source App stopped reaching the Source Repository between the preconditions and this read.
				return new Outcome.Deferred(this.clock.instant().plus(CUSTOMER_WAIT));
			}
			merges.ready(authorizationId, head, source.defaultBranch(), changes, workflows);
			return Outcome.succeeded();
		}
		catch (GitHubException failed) {
			return new Outcome.Transient(failed.status() == 0 ? "GITHUB_UNAVAILABLE" : "GITHUB_HTTP_" + failed.status());
		}
	}

	/**
	 * Which files under {@code .github/workflows/} differ between the Source Repository's default branch and the
	 * Intermediary's merge commit (AD-11): a file only one side has, or one whose blob differs. The run's
	 * {@code GITHUB_TOKEN} can push none of them, so a non-empty answer is {@code WORKFLOW_FILES_CHANGED}; the run
	 * checks its own push range as well, because a change made and reverted inside the range leaves the trees equal
	 * (deferred-work 626).
	 * @return the differing paths, by path, and an empty list when both trees hold the same workflow files; no answer
	 * at all when the Source App no longer reaches the Source Repository, which the caller waits for the Customer to
	 * fix and which never ends the Final Merge
	 * @throws GitHubException when GitHub cannot be reached
	 */
	static Optional<List<String>> workflowFiles(ProductSources sources, PlatformRepositories repositories,
			UUID productId, PlatformRepository intermediary, String head) {
		SourceWorkflows ours = sources.workflowFiles(productId);
		if (ours.block() != null) {
			// A Source block is the Customer's to fix, so the caller waits for them and uses up no attempt.
			return Optional.empty();
		}
		SortedMap<String, String> theirs = repositories.workflowFiles(intermediary, head);
		TreeSet<String> differing = new TreeSet<>();
		ours.files().forEach((path, blob) -> {
			if (!blob.equals(theirs.get(path))) {
				differing.add(path);
			}
		});
		theirs.keySet().stream().filter(path -> !ours.files().containsKey(path)).forEach(differing::add);
		return Optional.of(List.copyOf(differing));
	}

}
