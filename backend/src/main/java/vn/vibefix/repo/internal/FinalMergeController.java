package vn.vibefix.repo.internal;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import vn.vibefix.github.ActionsRuns;
import vn.vibefix.job.JobBusy;
import vn.vibefix.job.JobBusy.BusyJob;
import vn.vibefix.job.JobRoles;
import vn.vibefix.repo.internal.FinalMergeAuthorizations.Authorization;
import vn.vibefix.repo.internal.FinalMerges.FinalMerge;
import vn.vibefix.scanning.SensitiveChanges;
import vn.vibefix.shared.Access;
import vn.vibefix.shared.ApiException;
import vn.vibefix.shared.Caller;
import vn.vibefix.shared.CurrentCaller;
import vn.vibefix.shared.ErrorCode;
import vn.vibefix.shared.Gate;

/**
 * JOB-1's closure step "Chọn cách nhận mã" (FR-37, FR-38, UX-DR116, Story 23.9a), for the Job's Customer only: the
 * Final Merge with its FR-37 deadline, the whole Job's Sensitive Changes and N-18, the state of the run on the
 * Customer's own GitHub Actions, and, once vibefix has read {@code vibefix/final-{jobId}} back, the branch and
 * GitHub's compare page, where the Customer opens and merges the pull request themselves. vibefix opens no pull
 * request and writes nothing in a Customer repository. A Final Merge that could not run names its reason and keeps
 * the closure fallback offered. Everyone else finds nothing.
 */
@RestController
@Access(Gate.SIGNED_IN)
class FinalMergeController {

	/**
	 * The step.
	 * @param status {@code REQUESTED}, {@code USED}, or {@code ENDED}
	 * @param repository the Source Repository's full name
	 * @param deadlineAt the FR-37 deadline, from J8 on
	 * @param merge the Final Merge once the Job is Closing
	 * @param zip the closure fallback once the Final Merge cannot happen (Story 8.9)
	 * @param productId for "Đăng công việc ghép mã", which opens CUS-6 for the Product
	 */
	record FinalMergeView(UUID id, String status, String repository, @Nullable Instant deadlineAt,
			@Nullable String endReason, @Nullable MergeView merge, @Nullable ZipView zip, UUID productId) {
	}

	/**
	 * The closure fallback (FR-38), as Story 8.9 built it; Story 23.10 replaces it with the self-download path.
	 * @param status {@code BUILDING} or {@code READY}
	 * @param reason why the Final Merge could not happen
	 * @param fileId the archive, served by {@code GET /api/v1/files/{id}} to the Customer alone
	 */
	record ZipView(String status, String reason, @Nullable UUID fileId, List<MergeSensitiveChangeView> sensitive) {
	}

	/**
	 * The Final Merge (FR-37, SG-19, UX-DR35, UX-DR116).
	 * @param status {@code CHECKING}, {@code READY}, {@code CONFIRMED}, {@code RUNNING}, {@code DONE},
	 * {@code CONFLICT}, or {@code REFUSED}
	 * @param sensitive the whole Job's Sensitive Changes
	 * @param workflows the files under {@code .github/workflows/} that differ between the Source Repository and the
	 * accepted code: a non-empty list is N-18's workflow-file state, and nothing is dispatched
	 * @param refusal the named reason a Final Merge that cannot run shows
	 * @param run the run on the Customer's own GitHub Actions, once one was dispatched
	 * @param branch {@code vibefix/final-{jobId}}, the branch the run pushes
	 * @param branchUrl "Xem nhánh ↗", once the branch read back
	 * @param compareUrl "Mở trang hợp nhất trên GitHub ↗", GitHub's compare page, once the branch read back
	 */
	record MergeView(String status, List<MergeSensitiveChangeView> sensitive, List<String> workflows,
			@Nullable String refusal, @Nullable RunView run, @Nullable String branch, @Nullable String branchUrl,
			@Nullable String compareUrl) {
	}

	/**
	 * The Final Merge run on the Customer's own GitHub Actions (AD-46).
	 * @param state {@code DISPATCHED} before it was read, then {@code QUEUED}, {@code IN_PROGRESS} or {@code COMPLETED}
	 * @param conclusion GitHub's conclusion once it completed ({@code success}, {@code failure}…), or
	 * {@code timed_out} once vibefix stopped waiting for it
	 * @param htmlUrl the run's page on GitHub, in the Customer's own repository
	 */
	record RunView(String state, @Nullable String conclusion, @Nullable String htmlUrl) {
	}

	/** One Sensitive Change: its path and kind, as the Delivery Report shows one. */
	record MergeSensitiveChangeView(String path, SensitiveChanges.Kind kind) {
	}

	/** "Tôi đã xem và muốn chép mã vào dự án của tôi." (N-18). */
	record ConfirmRequest(@AssertTrue boolean agreed) {
	}

	private final FinalMergeAuthorizations authorizations;

	private final FinalMerges merges;

	private final ZipFallbacks fallbacks;

	private final ActionsRuns runs;

	private final GitHubLinks links;

	private final JobBusy busy;

	private final CurrentCaller callers;

	private final JobRoles roles;

	FinalMergeController(FinalMergeAuthorizations authorizations, FinalMerges merges, ZipFallbacks fallbacks,
			ActionsRuns runs, GitHubLinks links, JobBusy busy, CurrentCaller callers, JobRoles roles) {
		this.roles = roles;
		this.authorizations = authorizations;
		this.merges = merges;
		this.fallbacks = fallbacks;
		this.runs = runs;
		this.links = links;
		this.busy = busy;
		this.callers = callers;
	}

	@GetMapping("/api/v1/jobs/{jobId}/final-merge")
	FinalMergeView view(@PathVariable UUID jobId) {
		BusyJob job = customerJob(jobId);
		Authorization authorization = this.authorizations.latest(job.jobId())
			.orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
		if (authorization.open()) {
			// A check that failed is started again here.
			this.merges.prepare(job.jobId());
		}
		return view(authorization);
	}

	/**
	 * "Chép mã vào dự án của tôi" (FR-37, N-18): the run is dispatched on the Customer's own GitHub Actions under the
	 * {@code FINAL_MERGE} busy marker, so the answer is the Final Merge and its state.
	 * @throws ApiException {@code CONFLICT FINAL_MERGE_NOT_READY}; {@code CONFLICT FINAL_MERGE_CONFIRMED};
	 * {@code CONFLICT WORKFLOW_FILES_CHANGED}; {@code JOB_BUSY}; {@code STALE}
	 */
	@PostMapping("/api/v1/jobs/{jobId}/final-merge/confirm")
	@ResponseStatus(HttpStatus.ACCEPTED)
	FinalMergeView confirm(@PathVariable UUID jobId, @Valid @RequestBody ConfirmRequest body) {
		BusyJob job = customerJob(jobId);
		this.merges.confirm(job, this.callers.get().requireAccountId());
		return view(this.authorizations.latest(job.jobId()).orElseThrow());
	}

	/**
	 * The closure fallback instead of the Final Merge, after its Confirmation dialog (FR-37, FR-38): the row ends
	 * {@code DECLINED} and the fallback follows; it cannot be undone.
	 * @throws ApiException {@code CONFLICT NO_AUTHORIZATION_OPEN}; {@code CONFLICT FINAL_MERGE_CONFIRMED}
	 */
	@PostMapping("/api/v1/jobs/{jobId}/final-merge/decline")
	FinalMergeView decline(@PathVariable UUID jobId) {
		BusyJob job = customerJob(jobId);
		Authorization authorization = this.authorizations.openOf(job.jobId())
			.orElseThrow(() -> new ApiException(ErrorCode.CONFLICT, RepoReason.NO_AUTHORIZATION_OPEN));
		if (this.merges.find(authorization.id()).filter(FinalMerges::confirmed).isPresent()) {
			throw new ApiException(ErrorCode.CONFLICT, RepoReason.FINAL_MERGE_CONFIRMED);
		}
		this.authorizations.end(authorization.id(), "DECLINED");
		return view(this.authorizations.find(authorization.id()).orElseThrow());
	}

	private BusyJob customerJob(UUID jobId) {
		Caller caller = this.callers.get();
		BusyJob job = this.busy.job(jobId).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
		if (!job.code() || !this.roles.roleOf(jobId, caller).isCustomer()) {
			throw new ApiException(ErrorCode.NOT_FOUND);
		}
		return job;
	}

	private FinalMergeView view(Authorization authorization) {
		MergeView merge = this.merges.find(authorization.id()).map(found -> merge(authorization, found)).orElse(null);
		ZipView zip = this.fallbacks.find(authorization.jobId())
			.filter(found -> found.authorizationId().equals(authorization.id()))
			.map(found -> new ZipView(found.status(), found.reason(), found.archiveFileId(),
					found.sensitive().stream().map(change -> new MergeSensitiveChangeView(change.path(), change.kind())).toList()))
			.orElse(null);
		return new FinalMergeView(authorization.id(), authorization.status(), authorization.repositoryName(),
				authorization.deadlineAt(), authorization.endReason(), merge, zip, authorization.productId());
	}

	/** The Final Merge as the closure section reads it; the branch's links only once vibefix read it back. */
	private MergeView merge(Authorization authorization, FinalMerge found) {
		boolean done = "DONE".equals(found.status());
		String branch = found.branch();
		String repository = this.links.repository(authorization.repositoryName());
		String base = found.sourceBranch();
		return new MergeView(found.status(),
				found.sensitive().stream().map(change -> new MergeSensitiveChangeView(change.path(), change.kind())).toList(),
				found.workflows(), found.refusal(), run(found), branch,
				done && branch != null ? repository + "/tree/" + branch : null,
				done && branch != null && base != null ? repository + "/compare/" + base + "..." + branch + "?expand=1" : null);
	}

	/** The run dispatched last, as the Final Merge last read it; its page, in the Customer's own repository. */
	private @Nullable RunView run(FinalMerge found) {
		UUID correlation = found.correlationId();
		if (correlation == null) {
			return null;
		}
		String url = this.runs.correlation(correlation).map(ActionsRuns.Correlation::runHtmlUrl).orElse(null);
		return new RunView(found.runStatus() == null ? "DISPATCHED" : found.runStatus(), found.runConclusion(), url);
	}

}
