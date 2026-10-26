package vn.vibefix.job.internal;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import vn.vibefix.identity.ActiveAccount;
import vn.vibefix.identity.MentorDirectory;
import vn.vibefix.identity.MentorDirectory.MentorCard;
import vn.vibefix.identity.Notices;
import vn.vibefix.identity.StudentDirectory.StudentCard;
import vn.vibefix.job.internal.Agreements.Content;
import vn.vibefix.job.internal.Agreements.Milestone;
import vn.vibefix.job.internal.CrewService.Row;
import vn.vibefix.job.internal.CrewService.TermsVersion;
import vn.vibefix.job.internal.Jobs.Job;
import vn.vibefix.product.ProductDirectory;
import vn.vibefix.product.ProductDirectory.ProductInfo;
import vn.vibefix.settings.Rule;
import vn.vibefix.settings.Settings;
import vn.vibefix.shared.Access;
import vn.vibefix.shared.ApiException;
import vn.vibefix.shared.Caller;
import vn.vibefix.shared.CrewSplit;
import vn.vibefix.shared.CurrentCaller;
import vn.vibefix.shared.ErrorCode;
import vn.vibefix.shared.Gate;
import vn.vibefix.shared.Role;
import vn.vibefix.team.Teams;

/**
 * JOB-6's Crew panel for the Job's Mentor and STU-4's assignment panel for a Crew member (FR-75,
 * AD-41). The Mentor's commands carry the {@code crewVersion} the panel loaded. A Student reads only
 * their own share and the assignment projection; the Customer never reaches any of this.
 */
@RestController
@Access(Gate.SIGNED_IN)
class CrewController {

	record CrewAddRequest(@NotNull UUID studentId, @NotNull Long crewVersion) {
	}

	record CrewRemoveRequest(@NotNull Long crewVersion) {
	}

	record CrewShareRequest(@NotNull UUID accountId, @NotNull @Min(0) @Max(100) Integer percent) {
	}

	record CrewTermsRequest(@NotNull Long crewVersion, @NotNull @Size(min = 2, max = 11) List<@Valid CrewShareRequest> shares) {
	}

	enum TeamState {
		FREE, IN_CREW, ON_CREW, NOT_VERIFIED
	}

	/** A member of the Mentor's Team as the "Thêm sinh viên" list shows them. */
	record CrewCandidateView(UUID studentId, String name, @Nullable String githubLogin, TeamState state,
			@Nullable String onCrewJob) {
	}

	/**
	 * One Crew row.
	 * @param confirmedLatest the row confirmed the latest Crew Terms version
	 * @param percent the row's share in the latest version, if a party of it
	 * @param leaving a waiting Crew change removes the row when it takes effect (Story 11.3)
	 */
	record CrewRowView(UUID rowId, UUID studentId, String name, @Nullable String githubLogin, String status,
			@Nullable Instant signedAt, boolean confirmedLatest, @Nullable Integer percent, @Nullable String releasedReason,
			boolean leaving) {
	}

	/**
	 * "Đổi nhóm thực hiện" on a running Job (Story 11.3, FR-75), for the Mentor only.
	 * @param held a Milestone is Held, so a change cannot be saved now
	 * @param waitingVersionNo the change still waiting, if any
	 * @param confirmed the parties of the waiting change who signed and confirmed it
	 * @param total the Students the waiting change names
	 */
	record CrewChangeView(boolean held, @Nullable Integer waitingVersionNo, @Nullable Integer inForceVersionNo, int confirmed,
			int total) {
	}

	record CrewMentorView(UUID accountId, String displayName, @Nullable String githubLogin, @Nullable Integer percent) {
	}

	record CrewTermsView(int versionNo, String status, Instant createdAt) {
	}

	record CrewMilestoneView(String title, @Nullable Long priceVnd) {
	}

	/**
	 * The Mentor's Crew panel.
	 * @param confirmedCount how many of the Crew confirmed; from J6 on, the Crew in force
	 * @param reconfirmOwed a lock after the Agreement confirmation left the Crew owing "Xác nhận lại nhóm
	 * thực hiện" (R-1 H1)
	 * @param change a running Job's Crew change, with large Crews on; {@code null} otherwise
	 */
	record CrewPanelView(long crewVersion, int capPercent, boolean largeCrews, boolean locked, boolean crewConfirmed,
			int confirmedCount, int total, CrewMentorView mentor, List<CrewRowView> rows, @Nullable CrewTermsView latest,
			List<CrewCandidateView> team, List<CrewMilestoneView> milestones, boolean reconfirmOwed,
			@Nullable CrewChangeView change) {
	}

	record CrewReconfirmRequest(@NotNull Long crewVersion) {
	}

	record CrewAssignmentMilestoneView(String title, @Nullable LocalDate dueOn, @Nullable Long priceVnd, @Nullable Long yourPartVnd) {
	}

	/**
	 * STU-4's assignment panel: the assignment projection (AD-41), the Student's own share only.
	 * @param jobState the Job's state, so STU-4 links to the Board once the Job is Active
	 * @param signed the Student already signed this Job's NDA
	 * @param sharePercent their share in the version shown: the latest one to confirm, or the one in force
	 * @param changeWaiting an active member is a party of a waiting Crew change they have not confirmed, so
	 * the panel shows that version to confirm (Story 11.3)
	 */
	record CrewAssignmentView(UUID jobId, String title, String jobState, String productName, String mentorName, String status,
			String scope,
			List<CrewAssignmentMilestoneView> milestones, @Nullable Integer sharePercent, int versionNo,
			@Nullable Instant versionSentAt, boolean signed, boolean codeJob, boolean commitEmailNoticeAcknowledged,
			boolean changeWaiting) {
	}

	private final CrewService crews;

	private final CrewChanges changes;

	private final Agreements agreements;

	private final Teams teams;

	private final MentorDirectory mentors;

	private final ProductDirectory products;

	private final Notices notices;

	private final Settings settings;

	private final ActiveAccount gate;

	private final CurrentCaller callers;

	private final JdbcClient jdbc;

	private final boolean largeCrews;

	CrewController(CrewService crews, CrewChanges changes, Agreements agreements, Teams teams, MentorDirectory mentors,
			ProductDirectory products, Notices notices, Settings settings, ActiveAccount gate, CurrentCaller callers,
			JdbcClient jdbc,
			@Value("${vibefix.features.large-crews:false}") boolean largeCrews) {
		this.crews = crews;
		this.changes = changes;
		this.agreements = agreements;
		this.teams = teams;
		this.mentors = mentors;
		this.products = products;
		this.notices = notices;
		this.settings = settings;
		this.gate = gate;
		this.callers = callers;
		this.jdbc = jdbc;
		this.largeCrews = largeCrews;
	}

	@GetMapping("/api/v1/mentor/jobs/{jobId}/crew")
	CrewPanelView crewPanel(@PathVariable UUID jobId) {
		return panel(mentorJob(mentor(), jobId));
	}

	@PostMapping("/api/v1/mentor/jobs/{jobId}/crew")
	CrewPanelView addCrewMember(@PathVariable UUID jobId, @Valid @RequestBody CrewAddRequest body) {
		UUID mentor = mentor();
		this.crews.add(mentor, jobId, body.studentId(), body.crewVersion());
		return panel(mentorJob(mentor, jobId));
	}

	@PostMapping("/api/v1/mentor/jobs/{jobId}/crew/{crewRowId}/remove")
	CrewPanelView removeCrewMember(@PathVariable UUID jobId, @PathVariable UUID crewRowId, @Valid @RequestBody CrewRemoveRequest body) {
		UUID mentor = mentor();
		this.crews.remove(mentor, jobId, crewRowId, body.crewVersion());
		return panel(mentorJob(mentor, jobId));
	}

	/** "Lưu và gửi cho nhóm xác nhận": a new Crew Terms version (FR-75). */
	@PutMapping("/api/v1/mentor/jobs/{jobId}/crew/terms")
	CrewPanelView saveCrewTerms(@PathVariable UUID jobId, @Valid @RequestBody CrewTermsRequest body) {
		UUID mentor = mentor();
		this.crews.saveTerms(mentor, jobId, body.crewVersion(), shares(body));
		return panel(mentorJob(mentor, jobId));
	}

	/**
	 * "Áp dụng khi mọi người đã xác nhận" (Story 11.3, FR-75): the Crew after a change on a running Job, as
	 * one share per party; it waits for every party's confirmation and for no Milestone to be Held.
	 */
	@PutMapping("/api/v1/mentor/jobs/{jobId}/crew/change")
	CrewPanelView changeCrew(@PathVariable UUID jobId, @Valid @RequestBody CrewTermsRequest body) {
		UUID mentor = mentor();
		this.changes.propose(mentor, jobId, body.crewVersion(), shares(body));
		return panel(mentorJob(mentor, jobId));
	}

	private static Map<UUID, Integer> shares(CrewTermsRequest body) {
		Map<UUID, Integer> shares = new LinkedHashMap<>();
		for (CrewShareRequest share : body.shares()) {
			if (shares.put(share.accountId(), share.percent()) != null) {
				throw new ApiException(ErrorCode.VALIDATION, JobReason.SHARES_INVALID);
			}
		}
		return shares;
	}

	/** "Xác nhận lại nhóm thực hiện" after a lock owed it (R-1 H1). */
	@PostMapping("/api/v1/mentor/jobs/{jobId}/crew/reconfirm")
	CrewPanelView reconfirmCrew(@PathVariable UUID jobId, @Valid @RequestBody CrewReconfirmRequest body) {
		UUID mentor = mentor();
		this.crews.reconfirm(mentor, jobId, body.crewVersion());
		return panel(mentorJob(mentor, jobId));
	}

	/** STU-4: the Student's assignments, newest first; anyone else finds nothing. */
	@GetMapping("/api/v1/crew/assignments")
	List<CrewAssignmentView> crewAssignments() {
		Caller caller = this.callers.get();
		if (!caller.hasRole(Role.STUDENT)) {
			throw new ApiException(ErrorCode.NOT_FOUND);
		}
		UUID student = caller.requireAccountId();
		List<CrewAssignmentView> views = new ArrayList<>();
		for (UUID jobId : this.crews.openJobsOf(student)) {
			job(jobId).ifPresent(job -> views.add(assignment(job, student)));
		}
		return views;
	}

	private CrewAssignmentView assignment(Job job, UUID student) {
		Row row = this.crews.rows(job.id()).stream()
			.filter(found -> found.studentId().equals(student) && !found.released())
			.findFirst()
			.orElseThrow();
		Optional<TermsVersion> waiting = this.changes.waiting(job.id());
		boolean changeWaiting = "ACTIVE".equals(row.status())
				&& waiting.filter(version -> version.shares().containsKey(student) && !version.confirmedRows().contains(row.id()))
					.isPresent();
		Optional<TermsVersion> latest = "ASSIGNED".equals(row.status()) || changeWaiting ? this.crews.latest(job.id())
				: this.crews.inForceTerms(job.id()).or(() -> this.crews.latest(job.id()));
		Integer percent = latest.map(version -> version.shares().get(student)).orElse(null);
		Content content = this.agreements.draft(job).map(Agreements.Draft::content).orElse(null);
		List<CrewAssignmentMilestoneView> milestones = new ArrayList<>();
		if (content != null) {
			for (Milestone milestone : content.milestones()) {
				Long part = null;
				if (percent != null && milestone.priceVnd() != null && job.mentorId() != null) {
					part = CrewSplit.split(milestone.priceVnd(), latest.get().shares(), job.mentorId()).getOrDefault(student, 0L);
				}
				milestones.add(new CrewAssignmentMilestoneView(milestone.title(), milestone.dueOn(), milestone.priceVnd(), part));
			}
		}
		String mentorName = job.mentorId() == null ? ""
				: this.mentors.cards(List.of(job.mentorId())).stream().findFirst().map(MentorCard::displayName).orElse("");
		return new CrewAssignmentView(job.id(), job.title(), job.state().name(),
				this.products.find(job.productId()).map(ProductInfo::name).orElse(""), mentorName, row.status(),
				content == null ? "" : content.scope(), milestones, percent, latest.map(TermsVersion::versionNo).orElse(0),
				latest.map(TermsVersion::createdAt).orElse(null), row.signedAt() != null, job.code(),
				this.notices.acknowledged(student, Notices.COMMIT_EMAIL, Notices.COMMIT_EMAIL_VERSION), changeWaiting);
	}

	private CrewPanelView panel(Job job) {
		UUID mentorId = Objects.requireNonNull(job.mentorId());
		List<Row> rows = this.crews.rows(job.id());
		Optional<TermsVersion> latest = this.crews.latest(job.id());
		List<UUID> team = this.teams.members(mentorId);
		List<UUID> everyone = new ArrayList<>(team);
		rows.stream().map(Row::studentId).filter(id -> !everyone.contains(id)).forEach(everyone::add);
		Map<UUID, StudentCard> cards = this.crews.cards(everyone);
		Set<UUID> inCrew = rows.stream().filter(row -> !row.released()).map(Row::studentId).collect(Collectors.toSet());
		boolean changeable = this.changes.changeable(job.state());
		Optional<TermsVersion> waiting = changeable ? this.changes.waiting(job.id()) : Optional.empty();
		Map<UUID, String> elsewhere = this.crews.runningElsewhere(job.id(), team);
		List<CrewCandidateView> teamViews = team.stream().map(id -> {
			StudentCard card = cards.get(id);
			TeamState state = inCrew.contains(id) ? TeamState.IN_CREW
					: elsewhere.containsKey(id) ? TeamState.ON_CREW
							: card == null || !card.verified() ? TeamState.NOT_VERIFIED : TeamState.FREE;
			return new CrewCandidateView(id, name(card), card == null ? null : card.githubLogin(), state, elsewhere.get(id));
		}).toList();
		List<CrewRowView> rowViews = rows.stream().map(row -> {
			StudentCard card = cards.get(row.studentId());
			boolean confirmed = latest.map(version -> version.confirmedRows().contains(row.id())).orElse(false);
			boolean leaving = !row.released() && waiting.filter(version -> !version.shares().containsKey(row.studentId())).isPresent();
			return new CrewRowView(row.id(), row.studentId(), name(card), card == null ? null : card.githubLogin(), row.status(),
					row.signedAt(), confirmed, latest.map(version -> version.shares().get(row.studentId())).orElse(null),
					releasedReason(row), leaving);
		}).toList();
		MentorCard mentor = this.mentors.cards(List.of(mentorId)).stream().findFirst().orElse(null);
		List<CrewRowView> running = rowViews.stream().filter(row -> !"RELEASED".equals(row.status())).toList();
		int confirmedCount = (int) running.stream().filter(row -> row.signedAt() != null && row.confirmedLatest()).count();
		int total = running.size();
		Optional<TermsVersion> inForce = this.crews.inForceTerms(job.id());
		if (inForce.isPresent()) {
			// From J6 on JOB-H counts the Crew in force; a waiting change has its own line.
			List<Row> crewInForce = rows.stream().filter(row -> !row.released() && !"ASSIGNED".equals(row.status())).toList();
			total = crewInForce.size();
			confirmedCount = (int) crewInForce.stream().filter(row -> inForce.get().confirmedRows().contains(row.id())).count();
		}
		CrewChangeView change = null;
		if (changeable) {
			int confirmed = waiting.map(version -> (int) rows.stream()
				.filter(row -> !row.released() && version.shares().containsKey(row.studentId()) && row.signedAt() != null
						&& version.confirmedRows().contains(row.id()))
				.count()).orElse(0);
			change = new CrewChangeView(this.changes.held(job.id()), waiting.map(TermsVersion::versionNo).orElse(null),
					inForce.map(TermsVersion::versionNo).orElse(null), confirmed,
					waiting.map(version -> version.shares().size() - 1).orElse(0));
		}
		Content content = this.agreements.draft(job).map(Agreements.Draft::content).orElse(null);
		Boolean locked = this.jdbc.sql("""
				SELECT agreement_version_no IS NOT NULL AND crew_reconfirm_owed_at IS NULL FROM job.job WHERE id = :id
				""").param("id", job.id()).query(Boolean.class).single();
		return new CrewPanelView(this.crews.crewVersion(job.id()), this.settings.value(Rule.MENTOR_SHARE_CAP_PERCENT),
				this.largeCrews, locked || job.state() != JobState.AGREEMENT, this.crews.crewConfirmed(job.id()), confirmedCount,
				total,
				new CrewMentorView(mentorId, mentor == null ? "" : mentor.displayName(), mentor == null ? null : mentor.githubLogin(),
						latest.map(version -> version.shares().get(mentorId)).orElse(null)),
				rowViews, latest.map(version -> new CrewTermsView(version.versionNo(), version.status(), version.createdAt())).orElse(null),
				teamViews,
				content == null ? List.of()
						: content.milestones().stream().map(milestone -> new CrewMilestoneView(milestone.title(), milestone.priceVnd())).toList(),
				this.crews.reconfirmOwed(job.id()), change);
	}

	private @Nullable String releasedReason(Row row) {
		if (!row.released()) {
			return null;
		}
		return this.jdbc.sql("SELECT released_reason FROM job.crew_row WHERE id = :id").param("id", row.id()).query(String.class).single();
	}

	private static String name(@Nullable StudentCard card) {
		if (card == null) {
			return "";
		}
		return card.shownName();
	}

	private Optional<Job> job(UUID jobId) {
		return this.jdbc.sql("SELECT " + Jobs.COLUMNS + " FROM job.job WHERE id = :id")
			.param("id", jobId)
			.query(Jobs::job)
			.optional();
	}

	private Job mentorJob(UUID mentor, UUID jobId) {
		return job(jobId).filter(job -> mentor.equals(job.mentorId())).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
	}

	/** The Mentor calling, unlocked (AD-19 Job command); anyone else finds nothing. */
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
