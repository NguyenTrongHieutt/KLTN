package vn.vibefix.job.internal;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import vn.vibefix.identity.MentorGate;
import vn.vibefix.job.internal.Agreements.AccessLevel;
import vn.vibefix.job.internal.Agreements.Content;
import vn.vibefix.job.internal.Agreements.CriterionInput;
import vn.vibefix.job.internal.Agreements.Draft;
import vn.vibefix.job.internal.Agreements.Environment;
import vn.vibefix.job.internal.Agreements.ExternalAccessInput;
import vn.vibefix.job.internal.Agreements.MilestoneInput;
import vn.vibefix.job.internal.Agreements.Problem;
import vn.vibefix.job.internal.Agreements.SaveCommand;
import vn.vibefix.job.internal.Agreements.Version;
import vn.vibefix.job.internal.Agreements.VersionSummary;
import vn.vibefix.job.internal.AgreementReviews.Approval;
import vn.vibefix.job.internal.AgreementReviews.ChangeRequest;
import vn.vibefix.job.internal.AgreementReviews.Comment;
import vn.vibefix.job.internal.AgreementReviews.Confirmed;
import vn.vibefix.job.internal.AgreementReviews.TargetKind;
import vn.vibefix.job.internal.Jobs.Job;
import vn.vibefix.shared.Access;
import vn.vibefix.shared.ApiException;
import vn.vibefix.shared.Caller;
import vn.vibefix.shared.CurrentCaller;
import vn.vibefix.shared.ErrorCode;
import vn.vibefix.shared.Gate;
import vn.vibefix.shared.Role;

/**
 * JOB-6 "Thỏa thuận" (FR-16, FR-17, AD-29): the Job's Mentor reads, saves, shares, and confirms the
 * draft; the Customer reads the shared versions, comments, asks for changes, and approves, and finds
 * nothing before the first share (SG-31). Payment shares and {@code crewVersion} never reach the
 * Customer (AD-41).
 */
@RestController
@Access(Gate.SIGNED_IN)
class AgreementController {

	record CriterionRequest(@NotNull UUID id, @NotNull @Size(max = 500) String statement,
			@NotNull @Size(max = 2000) String checkSteps) {
	}

	record MilestoneRequest(@NotNull UUID id, @NotNull @Size(max = 120) String title,
			@Nullable @Min(1000) @Max(1_000_000_000) Long priceVnd, @Nullable LocalDate dueOn,
			@Min(0) @Max(10) int revisionRounds, @NotNull @Size(max = Agreements.MAX_CRITERIA) List<@Valid CriterionRequest> criteria) {
	}

	record ExternalAccessRequest(@NotNull UUID id, @NotNull @Size(max = 120) String system,
			@NotNull @Size(max = 300) String purpose, @NotNull Environment environment, @NotNull AccessLevel accessLevel) {
	}

	/** "Lưu bản nháp": the whole draft at the revision the editor loaded (AD-23). */
	record SaveRequest(@NotNull Integer revision, @NotNull @Size(max = 4000) String scope,
			@Nullable @Min(1000) @Max(1_000_000_000) Long priceVnd,
			@NotNull @Size(max = Agreements.MAX_SKILLS) List<UUID> skillIds,
			@NotNull @Size(max = Agreements.MAX_MILESTONES) List<@Valid MilestoneRequest> milestones,
			@NotNull @Size(max = Agreements.MAX_EXTERNAL_ACCESS) List<@Valid ExternalAccessRequest> externalAccess) {
	}

	record ShareRequest(@NotNull Integer revision) {
	}

	/** "Góp ý" on the scope ({@code targetId} absent) or one entity of the latest shared version. */
	record AgreementCommentRequest(@NotNull Integer versionNo, @NotNull TargetKind targetKind, @Nullable UUID targetId,
			@NotBlank @Size(max = 1000) String body) {
	}

	/** "Yêu cầu sửa", with the Milestone order when the Customer reordered. */
	record AgreementChangesRequest(@NotNull Long version, @NotNull Integer versionNo, @NotBlank @Size(max = 2000) String note,
			@Nullable @Size(max = Agreements.MAX_MILESTONES) List<UUID> milestoneOrder) {
	}

	/** "Duyệt thỏa thuận": N-6 for a Code Job and the External Access entries marked as live systems. */
	record AgreementApproveRequest(@NotNull Long version, @NotNull Integer versionNo, boolean freezeCommitment,
			@NotNull @Size(max = Agreements.MAX_EXTERNAL_ACCESS) List<UUID> liveAccessIds) {
	}

	/** "Xác nhận thỏa thuận" at the {@code Job.version} and {@code crewVersion} the screen loaded. */
	record AgreementConfirmRequest(@NotNull Long version, @NotNull Long crewVersion) {
	}

	record AgreementCommentView(UUID id, int versionNo, TargetKind targetKind, @Nullable UUID targetId, String body,
			Instant createdAt) {
	}

	record AgreementChangeRequestView(int versionNo, String note, @Nullable List<UUID> milestoneOrder, Instant createdAt) {
	}

	/** The confirmed Agreement: version, the approved draft, the confirmers' times, the usernames. */
	record ConfirmedAgreementView(int versionNo, int draftVersionNo, List<String> crew, List<UUID> liveAccessIds,
			boolean freezeCommitted, Instant approvedAt, Instant confirmedAt, Content content) {
	}

	/**
	 * What the Customer said about the latest shared version, for the Mentor's editor.
	 * @param approvedAt when the Customer approved the latest shared version, if they did
	 * @param draftMatchesShared the live draft says what the latest shared version says
	 */
	record AgreementReviewView(@Nullable Integer latestVersionNo, List<AgreementCommentView> comments,
			@Nullable AgreementChangeRequestView changeRequest, @Nullable Instant approvedAt, boolean draftMatchesShared) {
	}

	/** The selected Application's quote, which the draft is compared with (FR-16). */
	record QuoteView(long priceVnd, int timelineDays) {
	}

	/** The Mentor's editor: the live draft, what keeps it from being shared, and the Customer's review. */
	record AgreementDraftView(int revision, Content content, List<Problem> problems, @Nullable QuoteView quote,
			List<VersionSummary> versions, JobState state, AgreementReviewView review,
			@Nullable ConfirmedAgreementView confirmed) {
	}

	/**
	 * A shared version as the Customer reads it.
	 * @param crew the GitHub usernames of the Crew rows that are not released: never a share (FR-75)
	 * @param crewConfirmed FR-17's Crew condition holds; until then the Crew line reads as waiting
	 * @param agreementStartedAt J4's time, from which the timeline is compared with the quote
	 * @param changeRequested an open change request names this version
	 * @param approvedAt when the Customer approved this version, if they did
	 */
	record AgreementVersionView(int versionNo, Instant sharedAt, Content content,
			List<VersionSummary> versions, @Nullable QuoteView quote, List<String> crew, boolean crewConfirmed,
			int latestVersionNo, @Nullable Instant agreementStartedAt, List<AgreementCommentView> comments,
			boolean changeRequested, @Nullable Instant approvedAt, @Nullable ConfirmedAgreementView confirmed) {
	}

	private final Agreements agreements;

	private final Jobs jobs;

	private final MentorGate gate;

	private final CurrentCaller callers;

	private final JdbcClient jdbc;

	private final CrewService crews;

	private final AgreementReviews reviews;

	AgreementController(Agreements agreements, Jobs jobs, MentorGate gate, CurrentCaller callers, JdbcClient jdbc,
			CrewService crews, AgreementReviews reviews) {
		this.crews = crews;
		this.reviews = reviews;
		this.agreements = agreements;
		this.jobs = jobs;
		this.gate = gate;
		this.callers = callers;
		this.jdbc = jdbc;
	}

	@GetMapping("/api/v1/mentor/jobs/{jobId}/agreement")
	AgreementDraftView draft(@PathVariable UUID jobId) {
		Job job = mentorJob(mentor(), jobId);
		Draft draft = this.agreements.draft(job).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
		return view(job, draft);
	}

	@PutMapping("/api/v1/mentor/jobs/{jobId}/agreement")
	AgreementDraftView save(@PathVariable UUID jobId, @Valid @RequestBody SaveRequest body) {
		UUID mentor = mentor();
		Draft draft = this.agreements.save(mentor, jobId, new SaveCommand(body.revision(), body.scope(), body.priceVnd(),
				body.skillIds(),
				body.milestones().stream().map(milestone -> new MilestoneInput(milestone.id(), milestone.title(),
						milestone.priceVnd(), milestone.dueOn(), milestone.revisionRounds(),
						milestone.criteria().stream()
							.map(criterion -> new CriterionInput(criterion.id(), criterion.statement(), criterion.checkSteps()))
							.toList())).toList(),
				body.externalAccess().stream().map(access -> new ExternalAccessInput(access.id(), access.system(),
						access.purpose(), access.environment(), access.accessLevel())).toList()));
		return view(mentorJob(mentor, jobId), draft);
	}

	/** "Gửi cho khách hàng" (FR-16). */
	@PostMapping("/api/v1/mentor/jobs/{jobId}/agreement/share")
	AgreementDraftView share(@PathVariable UUID jobId, @Valid @RequestBody ShareRequest body) {
		UUID mentor = mentor();
		this.agreements.share(mentor, jobId, body.revision());
		Job job = mentorJob(mentor, jobId);
		return view(job, this.agreements.draft(job).orElseThrow());
	}

	/** "Xác nhận thỏa thuận" (FR-17): the confirmed Agreement version 1, and the Crew locks. */
	@PostMapping("/api/v1/mentor/jobs/{jobId}/agreement/confirm")
	AgreementDraftView confirmAgreement(@PathVariable UUID jobId, @Valid @RequestBody AgreementConfirmRequest body) {
		UUID mentor = mentor();
		this.reviews.confirm(mentor, jobId, body.version(), body.crewVersion());
		Job job = mentorJob(mentor, jobId);
		return view(job, this.agreements.draft(job).orElseThrow());
	}

	/** "Góp ý" (FR-16). */
	@PostMapping("/api/v1/jobs/{jobId}/agreement/comments")
	@ResponseStatus(HttpStatus.CREATED)
	AgreementCommentView commentOnAgreement(@PathVariable UUID jobId, @Valid @RequestBody AgreementCommentRequest body) {
		return commentView(this.reviews.comment(customer(), jobId, body.versionNo(), body.targetKind(), body.targetId(),
				body.body()));
	}

	/** "Yêu cầu sửa" (FR-16). */
	@PostMapping("/api/v1/jobs/{jobId}/agreement/changes")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	void requestAgreementChanges(@PathVariable UUID jobId, @Valid @RequestBody AgreementChangesRequest body) {
		this.reviews.requestChanges(customer(), jobId, body.version(), body.versionNo(), body.note(), body.milestoneOrder());
	}

	/** "Duyệt thỏa thuận" (FR-17). */
	@PostMapping("/api/v1/jobs/{jobId}/agreement/approve")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	void approveAgreement(@PathVariable UUID jobId, @Valid @RequestBody AgreementApproveRequest body) {
		this.reviews.approve(customer(), jobId, body.version(), body.versionNo(), body.freezeCommitment(), body.liveAccessIds());
	}

	/** The Customer's Thỏa thuận tab: a shared version, the latest by default. */
	@GetMapping("/api/v1/jobs/{jobId}/agreement")
	AgreementVersionView customer(@PathVariable UUID jobId, @RequestParam(required = false) @Nullable Integer version) {
		Job job = this.jobs.find(customer(), jobId).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
		Version shared = this.agreements.version(job.id(), version).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
		int latest = this.agreements.version(job.id(), null).map(Version::versionNo).orElse(shared.versionNo());
		// Story 11.3, AD-41: from J6 on the Customer sees the Crew in force, never a waiting change's member.
		boolean started = this.crews.inForce(job.id()).isPresent();
		List<UUID> running = this.crews.rows(job.id())
			.stream()
			.filter(row -> !row.released() && !(started && "ASSIGNED".equals(row.status())))
			.map(CrewService.Row::studentId)
			.toList();
		List<String> usernames = running.stream()
			.map(this.crews.cards(running)::get)
			.filter(card -> card != null && card.githubLogin() != null)
			.map(card -> card.githubLogin())
			.toList();
		return new AgreementVersionView(shared.versionNo(), shared.sharedAt(), shared.content(),
				this.agreements.versions(job.id()), quote(job.id()), usernames, started || this.crews.crewConfirmed(job.id()), latest,
				startedAt(job.id()), this.reviews.comments(job.id(), shared.versionNo()).stream().map(this::commentView).toList(),
				this.reviews.openChangeRequest(job.id()).filter(request -> request.versionNo() == shared.versionNo()).isPresent(),
				this.reviews.approval(job.id(), shared.versionNo()).map(Approval::approvedAt).orElse(null),
				this.reviews.confirmed(job.id()).map(this::confirmedView).orElse(null));
	}

	private AgreementDraftView view(Job job, Draft draft) {
		Version shared = this.agreements.version(job.id(), null).orElse(null);
		Integer latest = shared == null ? null : shared.versionNo();
		AgreementReviewView review = new AgreementReviewView(latest,
				latest == null ? List.of() : this.reviews.comments(job.id(), latest).stream().map(this::commentView).toList(),
				this.reviews.openChangeRequest(job.id()).map(this::changeRequestView).orElse(null),
				latest == null ? null : this.reviews.approval(job.id(), latest).map(Approval::approvedAt).orElse(null),
				shared != null && this.reviews.draftMatchesShared(job, shared));
		return new AgreementDraftView(draft.revision(), draft.content(), this.agreements.problems(draft.content()),
				quote(job.id()), this.agreements.versions(job.id()), job.state(), review,
				this.reviews.confirmed(job.id()).map(this::confirmedView).orElse(null));
	}

	private AgreementCommentView commentView(Comment comment) {
		return new AgreementCommentView(comment.id(), comment.versionNo(), comment.targetKind(), comment.targetId(), comment.body(),
				comment.createdAt());
	}

	private AgreementChangeRequestView changeRequestView(ChangeRequest request) {
		return new AgreementChangeRequestView(request.versionNo(), request.note(), request.milestoneOrder(), request.createdAt());
	}

	private ConfirmedAgreementView confirmedView(Confirmed confirmed) {
		return new ConfirmedAgreementView(confirmed.versionNo(), confirmed.draftVersionNo(), confirmed.crew(),
				confirmed.liveAccessIds(), confirmed.freezeCommitted(), confirmed.approvedAt(), confirmed.confirmedAt(),
				confirmed.content());
	}

	/** J4's time: the Agreement deadline less the Agreement period (FR-40). */
	private @Nullable Instant startedAt(UUID jobId) {
		return this.jdbc.sql("SELECT agreement_due_at FROM job.job WHERE id = :id")
			.param("id", jobId)
			.query(Timestamp.class)
			.optional()
			.map(due -> due.toInstant().minus(Agreements.AGREEMENT_PERIOD))
			.orElse(null);
	}

	/** The Customer calling; anyone else finds nothing. */
	private UUID customer() {
		Caller caller = this.callers.get();
		if (!caller.hasRole(Role.CUSTOMER)) {
			throw new ApiException(ErrorCode.NOT_FOUND);
		}
		return caller.requireAccountId();
	}

	private @Nullable QuoteView quote(UUID jobId) {
		return this.jdbc.sql("""
				SELECT quote_price_vnd, quote_timeline_days FROM job.job
				WHERE id = :id AND quote_price_vnd IS NOT NULL AND quote_timeline_days IS NOT NULL
				""").param("id", jobId)
			.query((rs, n) -> new QuoteView(rs.getLong("quote_price_vnd"), rs.getInt("quote_timeline_days")))
			.optional()
			.orElse(null);
	}

	private Job mentorJob(UUID mentor, UUID jobId) {
		return this.jdbc.sql("SELECT " + Jobs.COLUMNS + " FROM job.job WHERE id = :id AND mentor_account_id = :mentor")
			.param("id", jobId)
			.param("mentor", mentor)
			.query(Jobs::job)
			.optional()
			.orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
	}

	/** The Mentor calling, unlocked; anyone else finds nothing. */
	private UUID mentor() {
		Caller caller = this.callers.get();
		if (!caller.hasRole(Role.MENTOR)) {
			throw new ApiException(ErrorCode.NOT_FOUND);
		}
		UUID mentor = caller.requireAccountId();
		this.gate.requireActiveAccount(mentor);
		return mentor;
	}

}
