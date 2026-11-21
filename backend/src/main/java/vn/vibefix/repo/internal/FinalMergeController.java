package vn.vibefix.repo.internal;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonProperty;
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
import jakarta.validation.constraints.NotNull;
import vn.vibefix.github.ActionsRuns;
import vn.vibefix.job.JobBusy;
import vn.vibefix.job.JobBusy.BusyJob;
import vn.vibefix.job.JobRole;
import vn.vibefix.job.JobRoles;
import vn.vibefix.repo.internal.CodeReceipts.CodeReceipt;
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
 * JOB-1's closure step "Chọn cách nhận mã" (FR-37, FR-38, D10, UX-DR116, Stories 23.9a and 23.10a): the closing
 * choice with its deadline, and the chosen path's own states. The Customer reads the whole step -- the Final Merge's
 * Sensitive Changes and N-18, the state of the run on their own GitHub Actions, and, once vibefix has read
 * {@code vibefix/final-{jobId}} back, the branch and GitHub's compare page, where they open and merge the pull
 * request themselves -- and the self-download path, which they confirm with
 * {@code POST .../code-receipt}. vibefix opens no pull request and writes nothing in a Customer repository. A Final
 * Merge that could not run names its reason and leaves the self-download offered.
 * <p>
 * The Mentor reads the choice and its outcome only: the deadline, which paths are still open, how the code reached
 * the Customer, and -- since Story 23.10b -- that it never did, never the Customer's own actions, their Sensitive
 * Changes or their download link (EXPERIENCE.md's closure section). Everyone else finds nothing.
 * <p>
 * Story 23.10b also serves a Job whose handover work is still only in the Intermediary Repository but which never
 * had a closing-choice row -- a cancellation at J10 before one was opened: the self-download alone, no deadline and
 * no outcome, so the Customer can still receive their code and the download reminders have somewhere to lead. A Job
 * with neither a row nor work left to fetch is a 404 as before.
 */
@RestController
@Access(Gate.SIGNED_IN)
class FinalMergeController {

	/**
	 * The step.
	 * @param id the closing choice's row, or the Job itself when the Job never had one (Story 23.10b)
	 * @param status {@code REQUESTED}, {@code USED} (the choice is spent), or {@code ENDED}, which a Job with no row
	 * reads as too
	 * @param repository the Source Repository's full name
	 * @param deadlineAt the FR-37 deadline, from J8 on
	 * @param audience {@code CUSTOMER} or {@code MENTOR}
	 * @param choice the closing choice: its deadline, and which of the two paths is open
	 * @param outcome how the code reached the Customer: {@code MERGED}, {@code SELF_DOWNLOAD}, {@code UNCONFIRMED}
	 * (the deadline passed with neither path taken, Story 23.10b), or none yet
	 * @param receipt the Customer's own code receipt, once recorded; never shown to the Mentor
	 * @param merge the Final Merge once the Job is Closing; the Customer's alone
	 * @param productId for "Đăng công việc ghép mã", which opens CUS-6 for the Product
	 */
	record FinalMergeView(UUID id, String status, String repository, @Nullable Instant deadlineAt,
			@Nullable String endReason, String audience, ChoiceView choice, @Nullable String outcome,
			@Nullable ReceiptView receipt, @Nullable MergeView merge, UUID productId) {
	}

	/**
	 * The closing choice (FR-37, FR-38, D10, UX-DR116): two cards, neither preselected, under the Deadline chip
	 * "Chọn trước {date}".
	 * @param near the deadline is within 48 h, so the chip warns
	 * @param finalMerge "Hợp nhất vào dự án của bạn" may still be set going
	 * @param selfDownload "Tôi đã tự tải / clone từ GitHub" may still be confirmed
	 * @param intermediaryUrl "Mở kho trung gian ↗", the Customer's own read until the repositories are deleted
	 * @param deletionAt when the repositories are deleted, once the Job has ended
	 */
	record ChoiceView(@Nullable Instant deadlineAt, boolean near, boolean finalMerge, boolean selfDownload,
			@Nullable String intermediaryUrl, @Nullable Instant deletionAt) {
	}

	/** The code receipt: who confirmed they have the code, and when (FR-38, Story 23.10a). */
	record ReceiptView(UUID confirmedBy, Instant confirmedAt) {
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
	 * @param waiting {@code wait} in the API (a record cannot name a component after {@code Object.wait}): what a
	 * {@code CHECKING} Final Merge waits for and when it looks again (Story 24.5b); none while it checks, and none
	 * once that time has passed, when the check is due or retrying after a GitHub error
	 */
	record MergeView(String status, List<MergeSensitiveChangeView> sensitive, List<String> workflows,
			@Nullable String refusal, @Nullable RunView run, @Nullable String branch, @Nullable String branchUrl,
			@Nullable String compareUrl, @JsonProperty("wait") @Nullable MergeWaitView waiting) {
	}

	/**
	 * What the check waits for (Story 24.5b), which the closing card words for the Customer.
	 * @param reason {@code REVOCATION} (the team's access on GitHub), {@code CUSTOMER} (a Source block they fix on
	 * CUS-4) or {@code FROZEN} (a dispute)
	 * @param nextCheckAt when the check looks again; none for a revocation not yet read back, whose read-back starts
	 * the check itself
	 */
	record MergeWaitView(FinalMerges.Wait reason, @Nullable Instant nextCheckAt) {
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

	/**
	 * "Bạn đã có bản mã cuối cùng?" (FR-38, Story 23.10a).
	 * @param version the Job's version, which every command carries (AD-23)
	 */
	record CodeReceiptRequest(@AssertTrue boolean confirmed, @NotNull Long version) {
	}

	/** Who reads the step, and so how much of it they read. */
	private record Viewer(BusyJob job, boolean customer) {
	}

	private final FinalMergeAuthorizations authorizations;

	private final FinalMerges merges;

	private final CodeReceipts receipts;

	private final ActionsRuns runs;

	private final GitHubLinks links;

	private final JobBusy busy;

	private final CurrentCaller callers;

	private final JobRoles roles;

	/** A wait is shown only until its time (Story 24.5b). */
	private final Clock clock;

	FinalMergeController(FinalMergeAuthorizations authorizations, FinalMerges merges, CodeReceipts receipts,
			ActionsRuns runs, GitHubLinks links, JobBusy busy, CurrentCaller callers, JobRoles roles, Clock clock) {
		this.roles = roles;
		this.clock = clock;
		this.authorizations = authorizations;
		this.merges = merges;
		this.receipts = receipts;
		this.runs = runs;
		this.links = links;
		this.busy = busy;
		this.callers = callers;
	}

	@GetMapping("/api/v1/jobs/{jobId}/final-merge")
	FinalMergeView view(@PathVariable UUID jobId) {
		Viewer viewer = viewer(jobId);
		Authorization authorization = this.authorizations.latest(viewer.job().jobId()).orElse(null);
		if (authorization == null && !this.receipts.step(viewer.job())) {
			// No closing choice was ever made here, and this Job has neither ended with work left to fetch nor a
			// receipt of its own to show: there is no step at all.
			throw new ApiException(ErrorCode.NOT_FOUND);
		}
		if (viewer.customer() && authorization != null && authorization.open()) {
			// A check that failed is started again here; the Mentor's read starts nothing.
			this.merges.prepare(viewer.job().jobId());
		}
		return view(viewer, authorization);
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
		Viewer viewer = customer(jobId);
		this.merges.confirm(viewer.job(), this.callers.get().requireAccountId());
		return view(viewer, this.authorizations.latest(viewer.job().jobId()).orElse(null));
	}

	/**
	 * "Tôi đã tự tải / clone từ GitHub", after its Confirmation dialog (FR-38, D10, Story 23.10a): the receipt is
	 * recorded with the Customer and the instant, the choice is spent, and J9 follows. It cannot be undone.
	 * @throws ApiException {@code CONFLICT CODE_RECEIPT_RECORDED}; {@code CONFLICT FINAL_MERGE_DONE};
	 * {@code CONFLICT NO_CLOSING_CHOICE_OPEN}; {@code STALE}; {@code JOB_BUSY}
	 */
	@PostMapping("/api/v1/jobs/{jobId}/code-receipt")
	FinalMergeView codeReceipt(@PathVariable UUID jobId, @Valid @RequestBody CodeReceiptRequest body) {
		Viewer viewer = customer(jobId);
		this.receipts.confirm(viewer.job(), this.callers.get().requireAccountId(), body.version());
		// J9 has moved the Job, so the step is read again on what it left -- through the row the command required
		// before it wrote anything, or without one for a Job that never had a choice (Story 23.10b).
		return view(customer(jobId), this.authorizations.latest(jobId).orElse(null));
	}

	/** The Job's Customer or its Mentor, the two who read the closing choice; a Code Job only. */
	private Viewer viewer(UUID jobId) {
		Caller caller = this.callers.get();
		BusyJob job = this.busy.job(jobId).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
		JobRole role = this.roles.roleOf(jobId, caller);
		if (!job.code() || !(role.isCustomer() || role.isMentor())) {
			throw new ApiException(ErrorCode.NOT_FOUND);
		}
		return new Viewer(job, role.isCustomer());
	}

	/** Every command on the step is the Customer's own. */
	private Viewer customer(UUID jobId) {
		Viewer viewer = viewer(jobId);
		if (!viewer.customer()) {
			throw new ApiException(ErrorCode.NOT_FOUND);
		}
		return viewer;
	}

	/**
	 * The step for its viewer: the Customer reads all of it, the Mentor the choice and its outcome alone
	 * (EXPERIENCE.md's closure section). Without a closing-choice row the step is the Job's own: the Job's ID and
	 * Product, the Setup's Source Repository, {@code ENDED} with no reason, and no Final Merge of its own (Story
	 * 23.10b).
	 */
	private FinalMergeView view(Viewer viewer, @Nullable Authorization authorization) {
		UUID jobId = viewer.job().jobId();
		CodeReceipts.Choice choice = this.receipts.choice(viewer.job(), authorization, viewer.customer());
		CodeReceipt receipt = this.receipts.find(jobId).orElse(null);
		MergeView merge = viewer.customer() && authorization != null
				? this.merges.find(authorization.id()).map(found -> merge(authorization, found)).orElse(null) : null;
		return new FinalMergeView(authorization == null ? jobId : authorization.id(),
				authorization == null ? "ENDED" : authorization.status(),
				authorization == null ? this.receipts.source(jobId).orElse("") : authorization.repositoryName(),
				authorization == null ? null : authorization.deadlineAt(),
				authorization == null ? null : authorization.endReason(), viewer.customer() ? "CUSTOMER" : "MENTOR",
				new ChoiceView(choice.deadlineAt(), choice.near(), choice.finalMerge(), choice.selfDownload(),
						choice.intermediaryUrl(), choice.deletionAt()),
				outcome(authorization, receipt), viewer.customer() && receipt != null
						? new ReceiptView(receipt.confirmedBy(), receipt.confirmedAt()) : null,
				merge, viewer.job().productId());
	}

	/**
	 * How the code reached the Customer, which the Mentor reads too: the branch read back, or their own receipt.
	 * Neither, on a row the closing choice's deadline ended, is the third outcome: {@code UNCONFIRMED}, derived from
	 * {@code end_reason = 'DEADLINE'} and no evidence of either path, which no column of its own records (D10). A
	 * Job that never had a row never had a choice to miss, so it has no outcome at all.
	 */
	private @Nullable String outcome(@Nullable Authorization authorization, @Nullable CodeReceipt receipt) {
		if (authorization != null
				&& this.merges.find(authorization.id()).filter(found -> "DONE".equals(found.status())).isPresent()) {
			return "MERGED";
		}
		if (receipt != null) {
			return "SELF_DOWNLOAD";
		}
		return authorization != null && "DEADLINE".equals(authorization.endReason()) ? "UNCONFIRMED" : null;
	}

	/** The Final Merge as the closure section reads it; the branch's links only once vibefix read it back. */
	private MergeView merge(Authorization authorization, FinalMerge found) {
		boolean done = "DONE".equals(found.status());
		String branch = found.branch();
		String repository = this.links.repository(authorization.repositoryName());
		String base = found.sourceBranch();
		// A wait whose time has passed is over: the check is due, or retrying after a GitHub error.
		Instant next = found.nextCheckAt();
		FinalMerges.Wait waiting = "CHECKING".equals(found.status())
				&& (next == null || next.isAfter(this.clock.instant())) ? found.waitingFor() : null;
		return new MergeView(found.status(),
				found.sensitive().stream().map(change -> new MergeSensitiveChangeView(change.path(), change.kind())).toList(),
				found.workflows(), found.refusal(), run(found), branch,
				done && branch != null ? repository + "/tree/" + branch : null,
				done && branch != null && base != null ? repository + "/compare/" + base + "..." + branch + "?expand=1" : null,
				waiting == null ? null : new MergeWaitView(waiting, next));
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
