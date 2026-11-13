package vn.vibefix.repo.internal;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import org.jspecify.annotations.Nullable;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import vn.vibefix.github.ActionsRuns;
import vn.vibefix.github.GitHubException;
import vn.vibefix.identity.MentorDirectory;
import vn.vibefix.identity.PersonDirectory;
import vn.vibefix.job.Crews.Relationship;
import vn.vibefix.job.JobBusy;
import vn.vibefix.job.JobBusy.BusyJob;
import vn.vibefix.job.JobRole;
import vn.vibefix.job.JobRoles;
import vn.vibefix.product.ProductSources;
import vn.vibefix.product.ProductSources.Head;
import vn.vibefix.product.ProductSources.Registered;
import vn.vibefix.repo.internal.JobGrants.Grant;
import vn.vibefix.repo.internal.JobSetups.Block;
import vn.vibefix.repo.internal.JobSetups.Setup;
import vn.vibefix.repo.internal.JobSetups.Step;
import vn.vibefix.shared.Access;
import vn.vibefix.shared.ApiException;
import vn.vibefix.shared.Caller;
import vn.vibefix.shared.CurrentCaller;
import vn.vibefix.shared.ErrorCode;
import vn.vibefix.shared.Gate;

/**
 * JOB-H's step tracker while a Code Job is Starting (UX-DR23, UX-DR114, AD-11): the Setup's steps as the Customer,
 * the Mentor, and each active Crew member read them, the Customer's J7 self-confirmation of the head (N-32, R4,
 * Story 23.6b), and the Customer's "Thử lại" after a block. Everyone else finds nothing.
 */
@RestController
@Access(Gate.SIGNED_IN)
class JobSetupController {

	/** One step: {@code DONE}, {@code CURRENT}, {@code PENDING}, {@code BLOCKED}, or {@code FAILED}. */
	record SetupStepView(String key, String state) {
	}

	/**
	 * One person under "Mời người hướng dẫn và nhóm trên GitHub".
	 * @param role {@code MENTOR} or {@code CREW}
	 * @param name the Mentor's name; a Crew member's GitHub username for the Customer, else their name
	 * @param state {@code INVITING}, {@code INVITED}, {@code TWO_FACTOR_MISSING}, {@code ACCEPTED},
	 * {@code WRITE}, or {@code MEMBER_DRIFT}
	 * @param expiresAt a pending invitation's expiry
	 * @param githubUrl on the viewer's own row only: where they accept the invitation, or the repository
	 */
	record SetupPersonView(String role, String name, String state, @Nullable Instant expiresAt, @Nullable String githubUrl) {
	}

	/**
	 * The Source Repository's default-branch head the Customer is asked to confirm.
	 * @param readAt when vibefix read it
	 */
	record SetupHeadView(String sha, Instant readAt) {
	}

	/** The Customer's self-confirmation the copy runs for: the head they confirmed, and when. */
	record SetupConfirmationView(String sha, Instant confirmedAt) {
	}

	/**
	 * The copy run on the Customer's GitHub Actions (AD-46).
	 * @param state {@code DISPATCHED} before it was read, then {@code QUEUED}, {@code IN_PROGRESS} or {@code COMPLETED}
	 * @param conclusion GitHub's conclusion once it completed ({@code success}, {@code failure}…), or
	 * {@code timed_out} once vibefix stopped waiting for it
	 * @param htmlUrl the run's page on GitHub, in the Customer's own repository: for the Customer only
	 */
	record SetupRunView(String state, @Nullable String conclusion, @Nullable String htmlUrl) {
	}

	/**
	 * Who reads the step tracker, by their role in the Job (AD-43), which picks its words and its actions. An
	 * Administrator holds no role in any Job (FR-2), so this endpoint, which serves the Job's roles, never answers one.
	 */
	enum Viewer {

		CUSTOMER, MENTOR, CREW, ADMINISTRATOR

	}

	/**
	 * @param viewer the caller's role in the Job
	 * @param productId the Job's Product, whose page fixes the Source App (CUS-4)
	 * @param sourceFullName the Source Repository's {@code owner/name}: as the Setup read it with the head, else as the
	 * Product registered it
	 * @param defaultBranch its default branch, from the same read
	 * @param sourceUrl its page on GitHub
	 * @param status {@code NONE} before any Setup, else the latest attempt's {@code CONFIRMING} (waiting for the
	 * Customer's self-confirmation), {@code RUNNING}, {@code DONE}, {@code BLOCKED}, {@code FAILED}, or {@code ENDED}
	 * (the Job left Starting while it waited)
	 * @param blockedReason the plain reason of a block
	 * @param canRetry the viewer is the Customer and may start a new attempt
	 * @param head the head to confirm, once read
	 * @param confirmation the confirmation the copy runs for; none while the Setup waits for one
	 * @param run the copy run dispatched last, once there is one
	 * @param exchangeReason the exchange's refusal: of a block {@code EXCHANGE_REFUSED} (such as {@code blob}), or
	 * {@code sha} while the Setup waits for a confirmation of the head that moved
	 */
	record SetupView(UUID jobId, String jobState, Viewer viewer, UUID productId, @Nullable String sourceFullName,
			@Nullable String defaultBranch, @Nullable String sourceUrl, String status, List<SetupStepView> steps,
			@Nullable String blockedReason, boolean canRetry, List<SetupPersonView> people, @Nullable SetupHeadView head,
			@Nullable SetupConfirmationView confirmation, @Nullable SetupRunView run, @Nullable String exchangeReason) {
	}

	/**
	 * The Customer's self-confirmation (N-32).
	 * @param sha the head they confirm, as the step tracker showed it
	 * @param noticeVersion the version of N-32 they accepted
	 */
	record SetupConfirmationRequest(@NotNull @Pattern(regexp = "[0-9a-f]{40}") String sha,
			@NotNull @Pattern(regexp = JobSetups.NOTICE_VERSION_PATTERN) String noticeVersion) {
	}

	private final JobSetups setups;

	private final JobGrants grants;

	private final JobBusy busy;

	private final JobRoles roles;

	private final MentorDirectory mentors;

	private final PersonDirectory people;

	private final GitHubLinks links;

	private final ProductSources sources;

	private final ActionsRuns runs;

	private final CurrentCaller callers;

	JobSetupController(JobSetups setups, JobGrants grants, JobBusy busy, JobRoles roles, MentorDirectory mentors,
			PersonDirectory people, GitHubLinks links, ProductSources sources, ActionsRuns runs, CurrentCaller callers) {
		this.setups = setups;
		this.grants = grants;
		this.busy = busy;
		this.roles = roles;
		this.mentors = mentors;
		this.people = people;
		this.links = links;
		this.sources = sources;
		this.runs = runs;
		this.callers = callers;
	}

	@GetMapping("/api/v1/jobs/{jobId}/setup")
	SetupView setup(@PathVariable UUID jobId) {
		Caller caller = this.callers.get();
		BusyJob job = reader(jobId, caller);
		return view(job, caller);
	}

	/**
	 * "Thử lại" (FR-18): the Customer starts a new attempt once the latest one is blocked.
	 * @throws ApiException {@code GUARD_FAILED SELF_CONFIRMATION_MISSING} while it waits for their self-confirmation
	 */
	@PostMapping("/api/v1/jobs/{jobId}/setup/retry")
	SetupView retry(@PathVariable UUID jobId) {
		Caller caller = this.callers.get();
		BusyJob job = reader(jobId, caller);
		if (!customer(job, caller)) {
			throw new ApiException(ErrorCode.NOT_FOUND);
		}
		this.setups.retry(job);
		return view(this.busy.job(jobId).orElseThrow(), caller);
	}

	/**
	 * The Customer's J7 self-confirmation (R4, N-32): for the default-branch head the step tracker showed them, every
	 * credential in the history is revoked or rotated and end users' personal data are removed. The head is read again
	 * through their Source App first; at that head the confirmation is recorded and the copy starts.
	 * @throws ApiException {@code NOT_FOUND} for anyone but the Job's Customer; {@code CONFLICT SETUP_NOT_CONFIRMING}
	 * unless the Setup waits for a confirmation, its head read; {@code VALIDATION NOTICE_REQUIRED} for another N-32
	 * version; as {@link #retry} when the Job is no longer Starting, busy or frozen, before GitHub is read;
	 * {@code STALE} with {@code head}, the head read now, when it is not {@code sha} (the head changed after the page
	 * showed it); {@code GUARD_FAILED} with the Source App's block, which blocks the Setup while it still waits;
	 * {@code GUARD_FAILED GITHUB_UNAVAILABLE} when GitHub cannot be read now
	 */
	@PostMapping("/api/v1/jobs/{jobId}/setup/confirmation")
	SetupView confirmSourceClean(@PathVariable UUID jobId, @Valid @RequestBody SetupConfirmationRequest body) {
		Caller caller = this.callers.get();
		BusyJob job = reader(jobId, caller);
		if (!customer(job, caller)) {
			throw new ApiException(ErrorCode.NOT_FOUND);
		}
		Setup setup = this.setups.latest(jobId)
			.filter(latest -> "CONFIRMING".equals(latest.status()) && latest.checkedAt() != null)
			.orElseThrow(() -> new ApiException(ErrorCode.CONFLICT, RepoReason.SETUP_NOT_CONFIRMING));
		if (!this.setups.noticeVersion().equals(body.noticeVersion())) {
			throw new ApiException(ErrorCode.VALIDATION, RepoReason.NOTICE_REQUIRED);
		}
		ApiException refused = JobSetups.refusal(job);
		if (refused != null) {
			throw refused;
		}
		Head head;
		try {
			// Outside any transaction: GitHub is read before anything is written (A-03).
			head = this.sources.head(job.productId());
		}
		catch (GitHubException unavailable) {
			throw new ApiException(ErrorCode.GUARD_FAILED, RepoReason.GITHUB_UNAVAILABLE);
		}
		if (head.block() != null) {
			this.setups.blockWaiting(setup, Block.valueOf(head.block().name()));
			throw new ApiException(ErrorCode.GUARD_FAILED, RepoReason.valueOf(head.block().name()));
		}
		String current = Objects.requireNonNull(head.sha());
		if (!current.equals(body.sha())) {
			this.setups.headRead(setup, current);
			throw new ApiException(ErrorCode.STALE, null, Map.of("head", current));
		}
		this.setups.confirm(job, setup, caller.requireAccountId(), current);
		return view(this.busy.job(jobId).orElseThrow(), caller);
	}

	/** The Job's Customer, its Mentor, or an active Crew member, by their role in the Job (AD-43). */
	private BusyJob reader(UUID jobId, Caller caller) {
		BusyJob job = this.busy.job(jobId).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
		JobRole role = this.roles.roleOf(jobId, caller);
		if (!role.isCustomer() && !role.isMentor() && role.crewKind() != Relationship.ACTIVE) {
			throw new ApiException(ErrorCode.NOT_FOUND);
		}
		return job;
	}

	private boolean customer(BusyJob job, Caller caller) {
		return this.roles.roleOf(job.jobId(), caller).isCustomer();
	}

	private SetupView view(BusyJob job, Caller caller) {
		Setup setup = this.setups.latest(job.jobId()).orElse(null);
		JobRole role = this.roles.roleOf(job.jobId(), caller);
		Viewer viewer = caller.isAdministrator() ? Viewer.ADMINISTRATOR
				: role.isCustomer() ? Viewer.CUSTOMER : role.isMentor() ? Viewer.MENTOR : Viewer.CREW;
		// The name the Setup read with the head, which the confirmation is for; before any read, the registered one.
		String sourceFullName = setup == null ? null : setup.sourceFullName();
		String defaultBranch = setup == null ? null : setup.defaultBranch();
		if (sourceFullName == null || defaultBranch == null) {
			Registered registered = this.sources.registered(job.productId()).orElse(null);
			sourceFullName = registered == null ? null : registered.repositoryFullName();
			defaultBranch = registered == null ? null : registered.defaultBranch();
		}
		String sourceUrl = sourceFullName == null ? null : this.links.repository(sourceFullName);
		if (setup == null) {
			List<SetupStepView> pending = new ArrayList<>();
			for (Step step : Step.values()) {
				pending.add(new SetupStepView(step.name(), "PENDING"));
			}
			return new SetupView(job.jobId(), job.state(), viewer, job.productId(), sourceFullName, defaultBranch, sourceUrl,
					"NONE", pending, null, false, List.of(), null, null, null, null);
		}
		boolean customer = role.isCustomer();
		// A revoked grant is nobody's access any more (Story 7.7): it neither shows nor holds the Invite step.
		List<Grant> grants = this.grants.ofJob(job.jobId())
			.stream()
			.filter(grant -> !"REVOKING".equals(grant.status()) && !"REVOKED".equals(grant.status()))
			.toList();
		boolean active = !"STARTING".equals(job.state());
		boolean done = "DONE".equals(setup.status());
		boolean invited = done && !grants.isEmpty() && (active || grants.stream().allMatch(Grant::ready));
		boolean[] finished = { setup.checkedAt() != null, setup.confirmedAt() != null, setup.workingAt() != null || done,
				setup.copiedAt() != null || done, invited || active, active };
		// No step is done past the last one reached: a Setup that ended waiting shows nothing after its wait as done.
		for (int step = 1; step < finished.length; step++) {
			finished[step] = finished[step] && finished[step - 1];
		}
		List<SetupStepView> steps = new ArrayList<>();
		boolean reached = false;
		for (Step step : Step.values()) {
			String state;
			if (finished[step.ordinal()]) {
				state = "DONE";
			}
			else if (reached) {
				state = "PENDING";
			}
			else {
				reached = true;
				state = switch (setup.status()) {
					case "BLOCKED" -> "BLOCKED";
					case "FAILED" -> "FAILED";
					// The Job left Starting while it waited: nothing is current, and nothing failed.
					case "ENDED" -> "PENDING";
					default -> "CURRENT";
				};
			}
			steps.add(new SetupStepView(step.name(), state));
		}
		boolean canRetry = customer && "BLOCKED".equals(setup.status()) && "STARTING".equals(job.state());
		SetupHeadView head = setup.headSha() == null || setup.headReadAt() == null ? null
				: new SetupHeadView(setup.headSha(), setup.headReadAt());
		SetupConfirmationView confirmation = setup.approvedSha() == null || setup.confirmedAt() == null ? null
				: new SetupConfirmationView(setup.approvedSha(), setup.confirmedAt());
		return new SetupView(job.jobId(), job.state(), viewer, job.productId(), sourceFullName, defaultBranch, sourceUrl,
				setup.status(), steps, setup.block() == null ? null : setup.block().name(), canRetry,
				people(grants, caller, customer), head, confirmation, run(setup, customer), setup.exchangeReason());
	}

	/** The copy run dispatched last, as the Setup last read it; its page for the Customer, in whose repository it ran. */
	private @Nullable SetupRunView run(Setup setup, boolean customer) {
		if (setup.correlationId() == null) {
			return null;
		}
		String url = !customer ? null
				: this.runs.correlation(setup.correlationId()).map(ActionsRuns.Correlation::runHtmlUrl).orElse(null);
		return new SetupRunView(setup.runStatus() == null ? "DISPATCHED" : setup.runStatus(), setup.runConclusion(), url);
	}

	/** UX-DR23: the Customer sees the Mentor's name and the Crew's GitHub usernames only. */
	private List<SetupPersonView> people(List<Grant> grants, Caller caller, boolean customer) {
		Map<UUID, String> names = new HashMap<>();
		List<UUID> mentorIds = grants.stream().filter(grant -> "MENTOR".equals(grant.role())).map(Grant::accountId).toList();
		this.mentors.cards(mentorIds).forEach(card -> names.put(card.accountId(), card.displayName()));
		List<UUID> crewIds = grants.stream().filter(grant -> "CREW".equals(grant.role())).map(Grant::accountId).toList();
		if (!customer && !crewIds.isEmpty()) {
			this.people.cards(crewIds).stream()
				.filter(card -> card.name() != null)
				.forEach(card -> names.put(card.accountId(), card.name()));
		}
		List<SetupPersonView> people = new ArrayList<>();
		for (Grant grant : grants) {
			String name = names.getOrDefault(grant.accountId(), "@" + grant.login());
			String state = grant.twoFactorMissing() ? "TWO_FACTOR_MISSING" : grant.status();
			boolean own = grant.accountId().equals(caller.accountId());
			String url = !own ? null
					: "INVITED".equals(grant.status()) ? this.links.invitations(grant.repositoryName())
							: this.links.repository(grant.repositoryName());
			people.add(new SetupPersonView(grant.role(), name, state, "INVITED".equals(grant.status()) ? grant.expiresAt() : null,
					url));
		}
		return people;
	}

}
