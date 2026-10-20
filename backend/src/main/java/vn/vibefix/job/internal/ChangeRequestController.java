package vn.vibefix.job.internal;

import java.time.Instant;
import java.time.LocalDate;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import vn.vibefix.job.Crews.Relationship;
import vn.vibefix.job.internal.AgreementReviews.Confirmed;
import vn.vibefix.job.internal.ChangeRequests.ChangeRequest;
import vn.vibefix.job.internal.ChangeRequests.Item;
import vn.vibefix.job.internal.ChangeRequests.Party;
import vn.vibefix.job.internal.Jobs.Job;
import vn.vibefix.shared.Access;
import vn.vibefix.shared.ApiException;
import vn.vibefix.shared.Caller;
import vn.vibefix.shared.CurrentCaller;
import vn.vibefix.shared.ErrorCode;
import vn.vibefix.shared.Gate;
import vn.vibefix.shared.Role;

/**
 * JOB-6's "Đề nghị thay đổi" in its due-date variant (FR-36, UX-DR57) for the Customer and the Mentor,
 * and the confirmed Agreement versions every change leaves (AD-29), which the active Crew reads too.
 * Everyone else finds nothing.
 */
@RestController
@Access(Gate.SIGNED_IN)
class ChangeRequestController {

	record ChangeItemView(UUID milestoneId, int number, String title, @Nullable LocalDate oldDueOn, LocalDate newDueOn) {
	}

	/** @param status {@code OPEN}, {@code APPLIED}, {@code LAPSED}, or {@code WITHDRAWN} */
	record ChangeRequestView(UUID id, String proposer, boolean mine, @Nullable String description, @Nullable String impact,
			List<ChangeItemView> items, String status, @Nullable Instant customerApprovedAt,
			@Nullable Instant mentorConfirmedAt, @Nullable Integer agreementVersionNo, Instant createdAt,
			@Nullable Instant closedAt) {
	}

	/** @param changeable not Accepted, Cancelled, or Settled */
	record ChangeMilestoneView(UUID id, int number, String title, @Nullable LocalDate dueOn, String state,
			boolean changeable) {
	}

	/**
	 * @param version the Job version the next approval or confirmation names
	 * @param canPropose a confirmed Agreement, a running Job, and no open request
	 */
	record ChangeRequestsView(UUID jobId, String audience, long version, boolean canPropose,
			@Nullable ChangeRequestView open, List<ChangeRequestView> history, List<ChangeMilestoneView> milestones) {
	}

	record ChangeDueDate(@NotNull UUID milestoneId, @NotNull LocalDate newDueOn) {
	}

	record ChangeProposalRequest(@Nullable @Size(max = 2000) String description, @Nullable @Size(max = 1000) String impact,
			@NotNull @Size(min = 1, max = 50) List<@NotNull @Valid ChangeDueDate> items, @NotNull Long version) {
	}

	record ChangeImpactRequest(@NotBlank @Size(max = 1000) String impact) {
	}

	record ChangeVersionRequest(@NotNull Long version) {
	}

	record VersionMilestoneView(UUID id, String title, @Nullable LocalDate dueOn) {
	}

	record ConfirmedVersionView(int versionNo, Instant confirmedAt, List<VersionMilestoneView> milestones) {
	}

	record ConfirmedVersionsView(UUID jobId, List<ConfirmedVersionView> versions) {
	}

	private record Viewer(Job job, UUID accountId, @Nullable Party party) {
	}

	private static final Set<JobState> VISIBLE = EnumSet.of(JobState.AGREEMENT, JobState.STARTING,
			JobState.ACTIVE, JobState.CLOSING, JobState.CLOSED, JobState.CANCELLED);

	private final ChangeRequests requests;

	private final AgreementReviews reviews;

	private final CrewService crews;

	private final JdbcClient jdbc;

	private final CurrentCaller callers;

	ChangeRequestController(ChangeRequests requests, AgreementReviews reviews, CrewService crews, JdbcClient jdbc,
			CurrentCaller callers) {
		this.requests = requests;
		this.reviews = reviews;
		this.crews = crews;
		this.jdbc = jdbc;
		this.callers = callers;
	}

	@GetMapping("/api/v1/boards/{boardId}/change-requests")
	ChangeRequestsView changeRequests(@PathVariable("boardId") UUID jobId) {
		return view(party(jobId));
	}

	@PostMapping("/api/v1/boards/{boardId}/change-requests")
	@ResponseStatus(HttpStatus.CREATED)
	ChangeRequestsView propose(@PathVariable("boardId") UUID jobId, @Valid @RequestBody ChangeProposalRequest body) {
		Viewer viewer = party(jobId);
		Map<UUID, LocalDate> dueDates = new LinkedHashMap<>();
		body.items().forEach(item -> dueDates.put(item.milestoneId(), item.newDueOn()));
		if (dueDates.size() != body.items().size()) {
			throw new ApiException(ErrorCode.VALIDATION);
		}
		this.requests.propose(jobId, viewer.accountId(), viewer.party(), body.version(), body.description(),
				viewer.party() == Party.MENTOR ? body.impact() : null, dueDates);
		return view(party(jobId));
	}

	/** The Mentor's time impact on the Customer's proposal. */
	@PostMapping("/api/v1/change-requests/{changeRequestId}/impact")
	ChangeRequestsView impact(@PathVariable("changeRequestId") UUID requestId, @Valid @RequestBody ChangeImpactRequest body) {
		Viewer viewer = only(requestId, Party.MENTOR, JobReason.MENTOR_ONLY);
		this.requests.addImpact(requestId, viewer.accountId(), body.impact());
		return view(party(viewer.job().id()));
	}

	@PostMapping("/api/v1/change-requests/{changeRequestId}/approve")
	ChangeRequestsView approve(@PathVariable("changeRequestId") UUID requestId, @Valid @RequestBody ChangeVersionRequest body) {
		Viewer viewer = only(requestId, Party.CUSTOMER, JobReason.CUSTOMER_ONLY);
		this.requests.approve(requestId, viewer.accountId(), body.version());
		return view(party(viewer.job().id()));
	}

	@PostMapping("/api/v1/change-requests/{changeRequestId}/confirm")
	ChangeRequestsView confirm(@PathVariable("changeRequestId") UUID requestId, @Valid @RequestBody ChangeVersionRequest body) {
		Viewer viewer = only(requestId, Party.MENTOR, JobReason.MENTOR_ONLY);
		this.requests.confirm(requestId, viewer.accountId(), body.version());
		return view(party(viewer.job().id()));
	}

	/** The proposer withdraws it. */
	@PostMapping("/api/v1/change-requests/{changeRequestId}/withdraw")
	ChangeRequestsView withdraw(@PathVariable("changeRequestId") UUID requestId) {
		ChangeRequest request = this.requests.find(requestId).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
		Viewer viewer = party(request.jobId());
		if (viewer.party() != request.proposer()) {
			throw new ApiException(ErrorCode.FORBIDDEN, viewer.party() == Party.MENTOR ? JobReason.CUSTOMER_ONLY
					: JobReason.MENTOR_ONLY);
		}
		this.requests.withdraw(requestId, viewer.accountId());
		return view(party(request.jobId()));
	}

	/** Every confirmed version, the latest first (AD-29). */
	@GetMapping("/api/v1/boards/{boardId}/agreement-versions")
	ConfirmedVersionsView versions(@PathVariable("boardId") UUID jobId) {
		viewer(jobId, this.callers.get(), true);
		List<ConfirmedVersionView> versions = this.jdbc.sql("""
				SELECT version_no, confirmed_at, content::text AS content FROM job.confirmed_agreement WHERE job_id = :job
				ORDER BY version_no DESC
				""").param("job", jobId).query((rs, n) -> {
			Agreements.Content content = AgreementReviews.content(rs.getString("content"));
			return new ConfirmedVersionView(rs.getInt("version_no"), rs.getTimestamp("confirmed_at").toInstant(),
					content.milestones()
						.stream()
						.map(milestone -> new VersionMilestoneView(milestone.id(), milestone.title(), milestone.dueOn()))
						.toList());
		}).list();
		return new ConfirmedVersionsView(jobId, versions);
	}

	private Viewer party(UUID jobId) {
		Viewer viewer = viewer(jobId, this.callers.get(), false);
		if (viewer.party() == null) {
			throw new ApiException(ErrorCode.NOT_FOUND);
		}
		return viewer;
	}

	private Viewer only(UUID requestId, Party party, JobReason reason) {
		ChangeRequest request = this.requests.find(requestId).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
		Viewer viewer = party(request.jobId());
		if (viewer.party() != party) {
			throw new ApiException(ErrorCode.FORBIDDEN, reason);
		}
		return viewer;
	}

	/** The Customer, the Mentor, or (for the versions only) an active Crew member, from Agreement on. */
	private Viewer viewer(UUID jobId, Caller caller, boolean crew) {
		Job job = this.jdbc.sql("SELECT " + Jobs.COLUMNS + " FROM job.job WHERE id = :id")
			.param("id", jobId)
			.query(Jobs::job)
			.optional()
			.orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
		UUID account = caller.accountId();
		if (account == null || job.mentorId() == null || !VISIBLE.contains(job.state())) {
			throw new ApiException(ErrorCode.NOT_FOUND);
		}
		if (caller.hasRole(Role.CUSTOMER) && account.equals(job.customerId())) {
			return new Viewer(job, account, Party.CUSTOMER);
		}
		if (caller.hasRole(Role.MENTOR) && account.equals(job.mentorId())) {
			return new Viewer(job, account, Party.MENTOR);
		}
		if (crew && caller.hasRole(Role.STUDENT)
				&& this.crews.crewRelationship(jobId, account).kind() == Relationship.ACTIVE) {
			return new Viewer(job, account, null);
		}
		throw new ApiException(ErrorCode.NOT_FOUND);
	}

	private ChangeRequestsView view(Viewer viewer) {
		UUID jobId = viewer.job().id();
		ChangeRequest open = this.requests.open(jobId).orElse(null);
		Confirmed confirmed = this.reviews.confirmed(jobId).orElse(null);
		boolean canPropose = open == null && confirmed != null && ChangeRequests.RUNNING.contains(viewer.job().state());
		List<ChangeMilestoneView> milestones = this.requests.milestones(jobId)
			.stream()
			.map(milestone -> new ChangeMilestoneView(milestone.id(), milestone.number(), milestone.title(), milestone.dueOn(),
					milestone.state(), !ChangeRequests.FINAL.contains(milestone.state())))
			.toList();
		List<ChangeRequestView> history = this.requests.history(jobId)
			.stream()
			.filter(request -> !"OPEN".equals(request.status()))
			.map(request -> view(request, viewer))
			.toList();
		return new ChangeRequestsView(jobId, viewer.party() == null ? "CREW" : viewer.party().name(), viewer.job().version(),
				canPropose, open == null ? null : view(open, viewer), history, milestones);
	}

	private static ChangeRequestView view(ChangeRequest request, Viewer viewer) {
		List<ChangeItemView> items = request.items()
			.stream()
			.map((Item item) -> new ChangeItemView(item.milestoneId(), item.number(), item.title(), item.oldDueOn(),
					item.newDueOn()))
			.toList();
		return new ChangeRequestView(request.id(), request.proposer().name(), request.proposer() == viewer.party(),
				request.description(), request.impact(), items, request.status(), request.customerApprovedAt(),
				request.mentorConfirmedAt(), request.agreementVersionNo(), request.createdAt(), request.closedAt());
	}

}
