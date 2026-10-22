package vn.vibefix.job.internal;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
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
import vn.vibefix.job.internal.ChangeRequests.AddedCriterion;
import vn.vibefix.job.internal.ChangeRequests.AddedMilestone;
import vn.vibefix.job.internal.ChangeRequests.ChangeRequest;
import vn.vibefix.job.internal.ChangeRequests.Changes;
import vn.vibefix.job.internal.ChangeRequests.EditedCriterion;
import vn.vibefix.job.internal.ChangeRequests.Item;
import vn.vibefix.job.internal.ChangeRequests.MilestoneChange;
import vn.vibefix.job.internal.ChangeRequests.NewCriterion;
import vn.vibefix.job.internal.ChangeRequests.Party;
import vn.vibefix.job.internal.Jobs.Job;
import vn.vibefix.shared.Access;
import vn.vibefix.shared.ApiException;
import vn.vibefix.shared.Caller;
import vn.vibefix.shared.CurrentCaller;
import vn.vibefix.shared.ErrorCode;
import vn.vibefix.shared.Gate;
import vn.vibefix.shared.IdGenerator;
import vn.vibefix.shared.Role;

/**
 * JOB-6's "Đề nghị thay đổi" (FR-36, UX-DR57) for the Customer and the Mentor: due dates and, in the full
 * form, the scope, Milestone titles and prices, added or removed Milestones, and added, edited, or removed
 * Acceptance Criteria, never a share; and the confirmed Agreement versions every change leaves (AD-29),
 * which the active Crew reads too. Everyone else finds nothing.
 */
@RestController
@Access(Gate.SIGNED_IN)
class ChangeRequestController {

	record ChangeItemView(UUID milestoneId, int number, String title, @Nullable LocalDate oldDueOn, LocalDate newDueOn) {
	}

	/** One criterion as the form and a request show it. */
	record ChangeCriterionView(UUID id, String statement, String checkSteps) {
	}

	/** A live Milestone's new title or price, or its removal, with what it was. */
	record MilestoneChangeView(UUID milestoneId, @Nullable Integer number, String oldTitle, @Nullable String title,
			@Nullable Long oldPriceVnd, @Nullable Long priceVnd, boolean remove) {
	}

	record AddedMilestoneView(UUID id, String title, long priceVnd, LocalDate dueOn, int revisionRounds,
			List<ChangeCriterionView> criteria) {
	}

	/** @param kind {@code ADDED}, {@code EDITED}, or {@code REMOVED} */
	record CriterionChangeView(String kind, UUID criterionId, @Nullable Integer milestoneNumber, @Nullable String oldStatement,
			@Nullable String statement, @Nullable String checkSteps) {
	}

	/** What a request changes beyond due dates. */
	record ChangeSetView(@Nullable String scope, List<MilestoneChangeView> milestones, List<AddedMilestoneView> addedMilestones,
			List<CriterionChangeView> criteria) {
	}

	/** @param status {@code OPEN}, {@code APPLIED}, {@code LAPSED}, or {@code WITHDRAWN} */
	record ChangeRequestView(UUID id, String proposer, boolean mine, @Nullable String description, @Nullable String impact,
			List<ChangeItemView> items, String status, @Nullable Instant customerApprovedAt,
			@Nullable Instant mentorConfirmedAt, @Nullable Integer agreementVersionNo, Instant createdAt,
			@Nullable Instant closedAt, ChangeSetView changes) {
	}

	/**
	 * @param changeable not Accepted, Cancelled, or Settled
	 * @param held Funded, Revision Requested, or Submitted: its price waits for Story 14.2
	 */
	record ChangeMilestoneView(UUID id, int number, String title, @Nullable LocalDate dueOn, String state,
			boolean changeable, @Nullable Long priceVnd, boolean held, List<ChangeCriterionView> criteria) {
	}

	/**
	 * @param version the Job version the next approval or confirmation names
	 * @param canPropose a confirmed Agreement, a running Job, and no open request
	 * @param scope the confirmed Agreement's scope, which the full form edits
	 */
	record ChangeRequestsView(UUID jobId, String audience, long version, boolean canPropose,
			@Nullable ChangeRequestView open, List<ChangeRequestView> history, List<ChangeMilestoneView> milestones,
			@Nullable String scope) {
	}

	record ChangeDueDate(@NotNull UUID milestoneId, @NotNull LocalDate newDueOn) {
	}

	record MilestoneChangeRequest(@NotNull UUID milestoneId, @Nullable @Size(max = 120) String title,
			@Nullable Long priceVnd, boolean remove) {
	}

	record NewCriterionRequest(@NotBlank @Size(max = 500) String statement, @NotBlank @Size(max = 2000) String checkSteps) {
	}

	record AddedMilestoneRequest(@NotBlank @Size(max = 120) String title, @NotNull Long priceVnd, @NotNull LocalDate dueOn,
			@NotNull Integer revisionRounds, @NotNull @Size(min = 1, max = 30) List<@NotNull @Valid NewCriterionRequest> criteria) {
	}

	record AddedCriterionRequest(@NotNull UUID milestoneId, @NotBlank @Size(max = 500) String statement,
			@NotBlank @Size(max = 2000) String checkSteps) {
	}

	record EditedCriterionRequest(@NotNull UUID criterionId, @NotBlank @Size(max = 500) String statement,
			@NotBlank @Size(max = 2000) String checkSteps) {
	}

	/** The full form; every list but the due dates may be left out. */
	record ChangeProposalRequest(@Nullable @Size(max = 2000) String description, @Nullable @Size(max = 1000) String impact,
			@NotNull @Size(max = 50) List<@NotNull @Valid ChangeDueDate> items, @NotNull Long version,
			@Nullable @Size(max = 4000) String scope, @Nullable @Size(max = 50) List<@NotNull @Valid MilestoneChangeRequest> milestones,
			@Nullable @Size(max = 20) List<@NotNull @Valid AddedMilestoneRequest> addedMilestones,
			@Nullable @Size(max = 100) List<@NotNull @Valid AddedCriterionRequest> addedCriteria,
			@Nullable @Size(max = 100) List<@NotNull @Valid EditedCriterionRequest> editedCriteria,
			@Nullable @Size(max = 100) List<@NotNull UUID> removedCriteria) {
	}

	record ChangeImpactRequest(@NotBlank @Size(max = 1000) String impact) {
	}

	record ChangeVersionRequest(@NotNull Long version) {
	}

	record VersionMilestoneView(UUID id, String title, @Nullable LocalDate dueOn, @Nullable Long priceVnd,
			List<String> criteria) {
	}

	record ConfirmedVersionView(int versionNo, Instant confirmedAt, List<VersionMilestoneView> milestones, String scope,
			@Nullable Long priceVnd) {
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

	private final IdGenerator ids;

	ChangeRequestController(ChangeRequests requests, AgreementReviews reviews, CrewService crews, JdbcClient jdbc,
			CurrentCaller callers, IdGenerator ids) {
		this.ids = ids;
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
				viewer.party() == Party.MENTOR ? body.impact() : null, dueDates, changes(body));
		return view(party(jobId));
	}

	/** The request's changes, each added item given its once-created ID. */
	private Changes changes(ChangeProposalRequest body) {
		List<MilestoneChange> milestones = body.milestones() == null ? List.of() : body.milestones()
			.stream()
			.map(change -> new MilestoneChange(change.milestoneId(), change.title(), change.priceVnd(), change.remove()))
			.toList();
		List<AddedMilestone> added = body.addedMilestones() == null ? List.of() : body.addedMilestones()
			.stream()
			.map(milestone -> new AddedMilestone(this.ids.newId(), milestone.title(), milestone.priceVnd(), milestone.dueOn(),
					milestone.revisionRounds(), milestone.criteria()
						.stream()
						.map(criterion -> new NewCriterion(this.ids.newId(), criterion.statement(), criterion.checkSteps()))
						.toList()))
			.toList();
		List<AddedCriterion> addedCriteria = body.addedCriteria() == null ? List.of() : body.addedCriteria()
			.stream()
			.map(criterion -> new AddedCriterion(this.ids.newId(), criterion.milestoneId(), criterion.statement(),
					criterion.checkSteps()))
			.toList();
		List<EditedCriterion> edited = body.editedCriteria() == null ? List.of() : body.editedCriteria()
			.stream()
			.map(criterion -> new EditedCriterion(criterion.criterionId(), criterion.statement(), criterion.checkSteps()))
			.toList();
		String scope = body.scope() == null || body.scope().isBlank() ? null : body.scope();
		return new Changes(scope, milestones, added, addedCriteria, edited,
				body.removedCriteria() == null ? List.of() : body.removedCriteria());
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
						.map(milestone -> new VersionMilestoneView(milestone.id(), milestone.title(), milestone.dueOn(),
								milestone.priceVnd(), milestone.criteria().stream().map(Agreements.Criterion::statement).toList()))
						.toList(),
					content.scope(), content.priceVnd());
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
		Map<UUID, Agreements.Milestone> content = new HashMap<>();
		if (confirmed != null) {
			confirmed.content().milestones().forEach(milestone -> content.put(milestone.id(), milestone));
		}
		List<ChangeMilestoneView> milestones = this.requests.milestones(jobId)
			.stream()
			.map(milestone -> {
				Agreements.Milestone agreed = content.get(milestone.id());
				return new ChangeMilestoneView(milestone.id(), milestone.number(), milestone.title(), milestone.dueOn(),
						milestone.state(), !ChangeRequests.FINAL.contains(milestone.state()), agreed == null ? null : agreed.priceVnd(),
						ChangeRequests.HELD.contains(milestone.state()), agreed == null ? List.of() : agreed.criteria()
							.stream()
							.map(criterion -> new ChangeCriterionView(criterion.id(), criterion.statement(), criterion.checkSteps()))
							.toList());
			})
			.toList();
		List<ChangeRequestView> history = this.requests.history(jobId)
			.stream()
			.filter(request -> !"OPEN".equals(request.status()))
			.map(request -> view(request, viewer))
			.toList();
		return new ChangeRequestsView(jobId, viewer.party() == null ? "CREW" : viewer.party().name(), viewer.job().version(),
				canPropose, open == null ? null : view(open, viewer), history, milestones,
				confirmed == null ? null : confirmed.content().scope());
	}

	/** The Agreement a request was made against: the version before the one it wrote, else the current one. */
	private Agreements.@Nullable Content before(ChangeRequest request) {
		Integer written = request.agreementVersionNo();
		if (written != null && written > 1) {
			return this.jdbc.sql("SELECT content::text FROM job.confirmed_agreement WHERE job_id = :job AND version_no = :no")
				.param("job", request.jobId())
				.param("no", written - 1)
				.query(String.class)
				.optional()
				.map(AgreementReviews::content)
				.orElse(null);
		}
		return this.reviews.confirmed(request.jobId()).map(Confirmed::content).orElse(null);
	}

	private ChangeSetView changes(ChangeRequest request) {
		Changes changes = request.changes();
		Agreements.Content content = changes.isEmpty() ? null : before(request);
		Map<UUID, Integer> numbers = new HashMap<>();
		Map<UUID, Agreements.Milestone> milestones = new HashMap<>();
		Map<UUID, Agreements.Criterion> criteria = new HashMap<>();
		Map<UUID, Integer> criterionNumbers = new HashMap<>();
		if (content != null) {
			for (Agreements.Milestone milestone : content.milestones()) {
				numbers.put(milestone.id(), numbers.size() + 1);
				milestones.put(milestone.id(), milestone);
				for (Agreements.Criterion criterion : milestone.criteria()) {
					criteria.put(criterion.id(), criterion);
					criterionNumbers.put(criterion.id(), numbers.get(milestone.id()));
				}
			}
		}
		List<MilestoneChangeView> milestoneChanges = changes.milestones()
			.stream()
			.map(change -> {
				Agreements.Milestone was = milestones.get(change.milestoneId());
				return new MilestoneChangeView(change.milestoneId(), numbers.get(change.milestoneId()),
						was == null ? "" : was.title(), change.title(), was == null ? null : was.priceVnd(), change.priceVnd(),
						change.remove());
			})
			.toList();
		List<AddedMilestoneView> added = changes.addedMilestones()
			.stream()
			.map(milestone -> new AddedMilestoneView(milestone.id(), milestone.title(), milestone.priceVnd(), milestone.dueOn(),
					milestone.revisionRounds(), milestone.criteria()
						.stream()
						.map(criterion -> new ChangeCriterionView(criterion.id(), criterion.statement(), criterion.checkSteps()))
						.toList()))
			.toList();
		List<CriterionChangeView> criterionChanges = new ArrayList<>();
		changes.addedCriteria()
			.forEach(criterion -> criterionChanges.add(new CriterionChangeView("ADDED", criterion.id(),
					numbers.get(criterion.milestoneId()), null, criterion.statement(), criterion.checkSteps())));
		changes.editedCriteria().forEach(criterion -> {
			Agreements.Criterion was = criteria.get(criterion.criterionId());
			criterionChanges.add(new CriterionChangeView("EDITED", criterion.criterionId(),
					criterionNumbers.get(criterion.criterionId()), was == null ? null : was.statement(), criterion.statement(),
					criterion.checkSteps()));
		});
		changes.removedCriteria().forEach(id -> {
			Agreements.Criterion was = criteria.get(id);
			criterionChanges.add(new CriterionChangeView("REMOVED", id, criterionNumbers.get(id),
					was == null ? null : was.statement(), null, null));
		});
		return new ChangeSetView(changes.scope(), milestoneChanges, added, criterionChanges);
	}

	private ChangeRequestView view(ChangeRequest request, Viewer viewer) {
		List<ChangeItemView> items = request.items()
			.stream()
			.map((Item item) -> new ChangeItemView(item.milestoneId(), item.number(), item.title(), item.oldDueOn(),
					item.newDueOn()))
			.toList();
		return new ChangeRequestView(request.id(), request.proposer().name(), request.proposer() == viewer.party(),
				request.description(), request.impact(), items, request.status(), request.customerApprovedAt(),
				request.mentorConfirmedAt(), request.agreementVersionNo(), request.createdAt(), request.closedAt(),
				changes(request));
	}

}
