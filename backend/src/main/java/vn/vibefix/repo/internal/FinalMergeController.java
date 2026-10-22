package vn.vibefix.repo.internal;

import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import vn.vibefix.github.GitHubException;
import vn.vibefix.github.MergeApp;
import vn.vibefix.job.JobBusy;
import vn.vibefix.job.JobBusy.BusyJob;
import vn.vibefix.repo.internal.FinalMergeAuthorizations.Authorization;
import vn.vibefix.scanning.SensitiveChanges;
import vn.vibefix.shared.Access;
import vn.vibefix.shared.ApiException;
import vn.vibefix.shared.Caller;
import vn.vibefix.shared.CurrentCaller;
import vn.vibefix.shared.ErrorCode;
import vn.vibefix.shared.Gate;
import vn.vibefix.shared.Role;

/**
 * JOB-1's step "Cho phép hợp nhất" (FR-37, AD-10, UX-DR30, UX-DR55), for the Job's Customer only:
 * <ul>
 * <li>the authorization, its deadline, the last refused installation, and an install request waiting for
 * an organization owner or no longer listed; reading it while {@code REQUESTED} re-reads the requests;</li>
 * <li>"Cài ứng dụng hợp nhất trên GitHub ↗": a fresh install link with a single-use {@code state};</li>
 * <li>the Merge App's Setup URL, where GitHub returns: a valid {@code state} binds its authorization, and
 * any other return changes nothing; both go back to JOB-1, which refetches, marked when nothing was
 * installed (a request, or an organization that blocks installs).</li>
 * </ul>
 * Everyone else finds nothing.
 */
@RestController
@Access(Gate.SIGNED_IN)
class FinalMergeController {

	private static final Logger log = LoggerFactory.getLogger(FinalMergeController.class);

	/**
	 * The step.
	 * @param status {@code REQUESTED}, {@code BOUND}, {@code USED}, or {@code ENDED}
	 * @param repository the Source Repository's full name
	 * @param owner the account the Merge App is installed on, the repository's owner
	 * @param deadlineAt the FR-37 deadline, from J8 on
	 * @param refusal {@code MERGE_APP_WRONG_REPOSITORY} after a refused installation, until one binds
	 * @param requestOrg the organization whose owner must approve the waiting request
	 * @param requestRefused a request listed before and no longer, with nothing bound
	 * @param merge the Final Merge once the authorization is bound in Closing (Story 8.8)
	 */
	record FinalMergeView(UUID id, String status, String repository, String owner, @Nullable Instant deadlineAt,
			@Nullable String refusal, @Nullable String requestOrg, boolean requestWaiting, boolean requestRefused,
			@Nullable String endReason, @Nullable MergeView merge) {
	}

	/**
	 * The Final Merge (FR-37, SG-19, UX-DR35).
	 * @param status {@code CHECKING}, {@code READY}, {@code CONFIRMED}, {@code MERGED}, {@code CONFLICT}, or {@code REFUSED}
	 * @param sensitive the whole Job's Sensitive Changes
	 * @param workflows the workflow files already in the Source Repository, for the info alert
	 * @param pullUrl "Xem thay đổi trên GitHub ↗", once merged
	 */
	record MergeView(String status, List<MergeSensitiveChangeView> sensitive, List<String> workflows, @Nullable String pullUrl,
			@Nullable String refusal) {
	}

	/** One Sensitive Change: its path and kind, as the Delivery Report shows one. */
	record MergeSensitiveChangeView(String path, SensitiveChanges.Kind kind) {
	}

	/** "Tôi đã xem và đồng ý hợp nhất." (N-18). */
	record ConfirmRequest(@AssertTrue boolean agreed) {
	}

	/** Where "Cài ứng dụng hợp nhất trên GitHub ↗" goes. */
	record InstallLinkView(String url) {
	}

	private final FinalMergeAuthorizations authorizations;

	private final FinalMerges merges;

	private final MergeAppBindings bindings;

	private final MergeApp app;

	private final JobBusy busy;

	private final CurrentCaller callers;

	FinalMergeController(FinalMergeAuthorizations authorizations, FinalMerges merges, MergeAppBindings bindings, MergeApp app,
			JobBusy busy, CurrentCaller callers) {
		this.authorizations = authorizations;
		this.merges = merges;
		this.bindings = bindings;
		this.app = app;
		this.busy = busy;
		this.callers = callers;
	}

	@GetMapping("/api/v1/jobs/{jobId}/final-merge")
	FinalMergeView view(@PathVariable UUID jobId) {
		BusyJob job = customerJob(jobId);
		Authorization authorization = this.authorizations.latest(job.jobId())
			.orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
		if ("REQUESTED".equals(authorization.status())) {
			this.bindings.readRequests(List.of(authorization));
			authorization = this.authorizations.find(authorization.id()).orElseThrow();
		}
		else if ("BOUND".equals(authorization.status())) {
			// A check that failed is started again here (Story 8.8).
			this.merges.prepare(job.jobId());
		}
		return view(authorization);
	}

	/**
	 * "Hợp nhất vào dự án" (FR-37, N-18): the merge starts under the {@code FINAL_MERGE} busy marker.
	 * @throws ApiException {@code CONFLICT FINAL_MERGE_NOT_READY}; {@code JOB_BUSY}; {@code STALE}
	 */
	@PostMapping("/api/v1/jobs/{jobId}/final-merge/confirm")
	FinalMergeView confirm(@PathVariable UUID jobId, @Valid @RequestBody ConfirmRequest body) {
		BusyJob job = customerJob(jobId);
		this.merges.confirm(job, this.callers.get().requireAccountId());
		return view(this.authorizations.latest(job.jobId()).orElseThrow());
	}

	/** A fresh install link; the previous one's {@code state} no longer binds. */
	@PostMapping("/api/v1/jobs/{jobId}/final-merge/install-link")
	InstallLinkView installLink(@PathVariable UUID jobId) {
		BusyJob job = customerJob(jobId);
		String state = this.authorizations.newState(job.jobId())
			.orElseThrow(() -> new ApiException(ErrorCode.CONFLICT, RepoReason.NO_AUTHORIZATION_OPEN));
		return new InstallLinkView(this.app.installUrl(state));
	}

	/** GitHub's return to the Merge App's Setup URL (AD-10). */
	@GetMapping("/api/v1/github/merge-app/setup")
	ResponseEntity<Void> setup(@RequestParam(name = "installation_id", required = false) @Nullable Long installationId,
			@RequestParam(name = "setup_action", required = false) @Nullable String action,
			@RequestParam(required = false) @Nullable String state) {
		Caller caller = this.callers.get();
		Authorization authorization = state == null || state.isBlank() || !caller.hasRole(Role.CUSTOMER) ? null
				: this.authorizations.consume(state, caller.requireAccountId()).orElse(null);
		if (authorization == null) {
			return back("/cong-viec");
		}
		MergeAppBindings.Result result = MergeAppBindings.Result.UNKNOWN;
		if (installationId != null) {
			try {
				result = this.bindings.bindReturn(authorization, installationId);
			}
			catch (GitHubException unavailable) {
				// The installation event, or the Customer's next install, binds it later.
				log.warn("Merge App installation {} was not read on return: {}", installationId, unavailable.getMessage());
			}
		}
		// UX-DR55: a return that installed nothing, a request or a blocked install, says so on JOB-1.
		String page = FinalMergeAuthorizations.link(authorization.jobId());
		return back(result == MergeAppBindings.Result.UNKNOWN ? page + "?hop-nhat=chua-thay" : page);
	}

	private BusyJob customerJob(UUID jobId) {
		Caller caller = this.callers.get();
		BusyJob job = this.busy.job(jobId).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
		if (!job.code() || !caller.hasRole(Role.CUSTOMER) || !job.customerId().equals(caller.accountId())) {
			throw new ApiException(ErrorCode.NOT_FOUND);
		}
		return job;
	}

	private FinalMergeView view(Authorization authorization) {
		MergeView merge = this.merges.find(authorization.id())
			.map(found -> new MergeView(found.status(),
					found.sensitive().stream().map(change -> new MergeSensitiveChangeView(change.path(), change.kind())).toList(),
					found.workflows(), "MERGED".equals(found.status()) ? found.pullUrl() : null, found.refusal()))
			.orElse(null);
		return new FinalMergeView(authorization.id(), authorization.status(), authorization.repositoryName(),
				authorization.owner(), authorization.deadlineAt(), authorization.refusal(), authorization.requestOrg(),
				authorization.requestSeenAt() != null, authorization.requestRefusedAt() != null, authorization.endReason(),
				merge);
	}

	private static ResponseEntity<Void> back(String path) {
		return ResponseEntity.status(HttpStatus.SEE_OTHER).location(URI.create(path)).build();
	}

}
