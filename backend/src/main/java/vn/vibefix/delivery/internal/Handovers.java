package vn.vibefix.delivery.internal;

import java.net.URI;
import java.net.URISyntaxException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import vn.vibefix.delivery.internal.DeliveryAccess.Viewer;
import vn.vibefix.job.AgreementCriteria;
import vn.vibefix.job.AgreementCriteria.CriterionInForce;
import vn.vibefix.job.AgreementCriteria.MilestoneInForce;
import vn.vibefix.job.MilestoneHandovers;
import vn.vibefix.job.MilestoneHandovers.Submitted;
import vn.vibefix.job.MilestoneStates;
import vn.vibefix.notification.Audience;
import vn.vibefix.notification.TodoRequest;
import vn.vibefix.notification.Todos;
import vn.vibefix.repo.HandoverBranches.Commit;
import vn.vibefix.scanning.SensitiveChanges;
import vn.vibefix.scanning.SensitiveChanges.SensitiveChange;
import vn.vibefix.shared.ApiException;
import vn.vibefix.shared.ErrorCode;
import vn.vibefix.shared.IdGenerator;
import vn.vibefix.timers.DeadlineRequest;
import vn.vibefix.timers.Deadlines;

/**
 * Milestone Handovers (FR-10, FR-32, AD-4, AD-23, AD-33, SG-25): the preflight, the handover itself, and
 * its Delivery Report. A handover needs each Acceptance Criterion of the Milestone marked "Đạt" or
 * "Chưa đạt" with a note, which since Story 24.2 retired the Board is the whole of FR-32's enforcement;
 * the notes are stored as written (D5, Story 24.4b). Beside the criteria it carries up to {@value #MAX_LINKS} {@code https} links of the
 * Mentor's own tools, each a "Bản ghi / video" or a "Tài liệu" with a short label, which vibefix never fetches
 * (D3, AD-32, Story 24.3): when a preview is impossible, the recording is one of them. {@code job} applies M2
 * first, on the version the form loaded, so nothing is written when it refuses; then the handover, its criteria
 * and its links are stored, a Code Job's with its Handover Commit and range (Story 8.2), and the to-dos move
 * from the Mentor to the Customer. An Assessment Job hands over the Milestone itself: its criteria are the
 * Assessment Report's sections, and the report reaches the Customer as the handover's "Tài liệu" link, since
 * the platform stores no documents (FR-10).
 */
@Component
class Handovers {

	static final String HAND_OVER = "delivery.hand_over";

	static final String REVIEW_HANDOVER = "delivery.review_handover";

	static final int MAX_NOTE = 2000;

	/** How many links one handover carries (D3, FR-32): the eleventh is refused {@code TOO_MANY_LINKS}. */
	static final int MAX_LINKS = 10;

	/** A link's URL: {@code https} only and at most 500 characters, the preview link's own rule (AD-35). */
	static final String LINK_URL_PATTERN = "^https://\\S+$";

	static final int MAX_LINK_URL = 500;

	static final int MAX_LINK_LABEL = 80;

	private static final java.util.regex.Pattern LINK_URL = java.util.regex.Pattern.compile(LINK_URL_PATTERN);

	/** N-33's version, as the property and a Mentor's confirmation name it: major.minor, such as {@code 1.0}. */
	static final String NOTICE_VERSION_PATTERN = "[0-9]{1,3}\\.[0-9]{1,3}";

	private static final java.util.regex.Pattern NOTICE_VERSION = java.util.regex.Pattern.compile(NOTICE_VERSION_PATTERN);

	/**
	 * How many Sensitive Changes one handover stores (FR-32, Story 23.8a): the run reports every changed path, up
	 * to 100,000, and the Delivery Report lists the first of them with the full count beside it {@code [ASSUMPTION]}.
	 */
	static final int MAX_SENSITIVE_ROWS = 300;

	/** The Milestone states a handover leaves (M2). */
	static final Set<String> HANDABLE = Set.of("FUNDED", "REVISION_REQUESTED");

	/** The Mentor's mark on one criterion. */
	record CriterionMark(UUID criterionId, boolean met, String note) {
	}

	/**
	 * One link a handover carries (D3, FR-10, FR-32): a recording or a document the Mentor's own tools host, which
	 * vibefix never requests (AD-32); the screens show the domain from its text, with N-11 (AD-35).
	 * @param label the Mentor's short label, stored as written
	 */
	record Link(Kind kind, String url, String label) {

		/** "Bản ghi / video" or "Tài liệu". */
		enum Kind {
			VIDEO, DOCUMENT
		}

	}

	/**
	 * The Mentor's N-33 duty confirmation, as a Code Job's handover records it (FR-25, FR-32, R28, Story 23.8b):
	 * vibefix scans no team code, so the Mentor confirms where they hand over that the handover's commits hold no
	 * credential and no real data. An Assessment Job hands over no code, so it has none.
	 * @param accountId the Mentor who ticked it, which is the handover's own Mentor
	 * @param noticeVersion the version of N-33's Mentor text they were shown
	 * @param confirmedAt when "Gửi bàn giao" carried it, which is before the runner's copy
	 */
	record TeamCodeConfirmation(UUID accountId, String noticeVersion, Instant confirmedAt) {
	}

	/** @param auto accepted by Auto-acceptance (FR-35) */
	record Handover(UUID id, UUID jobId, UUID productId, UUID milestoneId, int number, String milestoneTitle, int round,
			@Nullable UUID mentorId, String status, long jobVersion, Instant checkBy, Instant createdAt,
			@Nullable Instant decidedAt, boolean auto) {
	}

	record Criterion(UUID criterionId, int number, String statement, String checkSteps, boolean met, String note) {
	}

	/**
	 * What the "Tạo bàn giao" form shows before anything is sent.
	 * @param problems the refusals the handover would meet now, as reason codes
	 * @param sensitive a Code Job's Sensitive Changes in the commits it would hand over
	 */
	record Preflight(MilestoneInForce milestone, String state, long jobVersion, boolean assessment,
			List<CriterionInForce> criteria, List<String> problems, List<SensitiveChange> sensitive, int commitCount) {
	}

	/**
	 * A handover checked, not yet written (FR-32); its texts are as the Mentor wrote them (D5).
	 * @param previewUrl the preview link, which the caller checks before {@link #record}
	 * @param noPreviewReason why a preview is impossible, as written; with at least one VIDEO link
	 * @param links the Mentor's links in their order, never fetched
	 */
	record Draft(MilestoneInForce milestone, List<CriterionInForce> criteria, Map<UUID, Boolean> met,
			Map<UUID, String> criterionNotes, @Nullable String previewUrl, @Nullable String noPreviewReason,
			List<Link> links, List<UUID> screenshotIds) {
	}

	/** One Acceptance Criterion as the Mentor marked it, ready to store. */
	record PendingCriterion(UUID criterionId, int number, String statement, String checkSteps, boolean met, String note) {
	}

	/**
	 * Everything a handover stores, checked, before M2 applies (FR-32, Story 23.8a). A Code Job keeps
	 * it in {@code delivery.handover_submission} while the runner copies the Handover Commit, so what the Mentor
	 * sent is recorded as they sent it, whatever moves meanwhile.
	 * @param id the handover's ID, which for a Code Job is also its submission's and its branch's
	 * @param version the Job's version M2 is applied on (AD-23)
	 * @param links the Mentor's links in their order (Story 24.3); a draft stored before that story was rewritten
	 * by its migration, and one that names none reads back with none
	 * @param teamCode the Mentor's N-33 confirmation for a Code Job; none for an Assessment Job (Story 23.8b)
	 */
	record Pending(UUID id, UUID jobId, UUID productId, UUID customerId, UUID mentorId, long version, UUID milestoneId,
			int milestoneNumber, String milestoneTitle, List<PendingCriterion> criteria, @Nullable String previewUrl,
			@Nullable String noPreviewReason, List<Link> links, List<UUID> screenshotIds,
			@Nullable TeamCodeConfirmation teamCode) {

		Pending {
			criteria = List.copyOf(criteria);
			links = links == null ? List.of() : List.copyOf(links);
			screenshotIds = List.copyOf(screenshotIds);
		}

		/** What the Mentor's drafted handover will store, under {@code id}. */
		static Pending of(Viewer mentor, Draft draft, long version, UUID id, @Nullable TeamCodeConfirmation teamCode) {
			return new Pending(id, mentor.job().jobId(), mentor.job().productId(), mentor.job().customerId(),
					mentor.accountId(), version, draft.milestone().milestoneId(), draft.milestone().number(),
					draft.milestone().title(),
					draft.criteria()
						.stream()
						.map(criterion -> new PendingCriterion(criterion.criterionId(), criterion.number(),
								criterion.statement(), criterion.checkSteps(), draft.met().get(criterion.criterionId()),
								draft.criterionNotes().get(criterion.criterionId())))
						.toList(),
					draft.previewUrl(), draft.noPreviewReason(), draft.links(), draft.screenshotIds(), teamCode);
		}

	}

	/**
	 * The preview link, or why a preview is impossible, and the links and images for "Gửi bàn giao" (FR-32, AD-35,
	 * Story 24.3).
	 */
	record Evidence(@Nullable String previewUrl, @Nullable String noPreviewReason, List<Link> links,
			List<UUID> screenshotIds) {

		static final Evidence NONE = new Evidence(null, null, List.of(), List.of());

		Evidence {
			links = List.copyOf(links);
			screenshotIds = List.copyOf(screenshotIds);
		}

	}

	/** What a handover carries to try the change (FR-32, AD-35); its recording, if any, is one of its links. */
	record Preview(@Nullable String previewUrl, @Nullable Instant previewCheckedAt, @Nullable String noPreviewReason) {
	}

	/**
	 * A Code Job's handover as the copy run on the runner left it (AD-11, AD-33, Story 23.8a).
	 * @param commit the Handover Commit
	 * @param rangeStart the previous Handover Commit, or the commit Setup copied
	 * @param truncated whether GitHub's compare cut the commit list, which it does past 250 commits; the changed
	 * paths come from the run's own report and are never cut, so {@code sensitive} covers the whole range (FR-32)
	 */
	record Code(String commit, String rangeStart, int pullNumber, List<Commit> commits, boolean truncated,
			List<SensitiveChange> sensitive) {
	}

	/** A recorded commit of a Code Job's handover. */
	record RecordedCommit(String sha, String message) {
	}

	/**
	 * What a Code Job's handover recorded, for its Delivery Report.
	 * @param sensitive the Sensitive Changes stored, at most {@value #MAX_SENSITIVE_ROWS} of them
	 * @param sensitiveCount how many the run's full list held, which the report's caption names
	 * @param teamCodeConfirmedAt when the Mentor ticked N-33 (Story 23.8b), which the Customer's own line quotes;
	 * {@code null} for a handover recorded before that story, which holds no confirmation to quote
	 */
	record CodeRecord(String commit, int pullNumber, int unattributed, boolean truncated, List<RecordedCommit> commits,
			List<SensitiveChange> sensitive, int sensitiveCount, @Nullable Instant teamCodeConfirmedAt) {
	}

	private static final String COLUMNS = """
			id, job_id, product_id, milestone_id, milestone_number, milestone_title, round, mentor_account_id, status,
			job_version, check_by, created_at, decided_at, auto
			""";

	private final JdbcClient jdbc;

	private final MilestoneHandovers jobs;

	private final AgreementCriteria criteria;

	private final MilestoneStates states;

	private final Todos todos;

	private final Deadlines deadlines;

	private final IdGenerator ids;

	private final Clock clock;

	private final HandoverScreenshots screenshots;

	private final String noticeVersion;

	Handovers(JdbcClient jdbc, MilestoneHandovers jobs, AgreementCriteria criteria, MilestoneStates states, Todos todos,
			Deadlines deadlines, IdGenerator ids, Clock clock, HandoverScreenshots screenshots,
			@Value("${vibefix.delivery.team-code-notice-version}") String noticeVersion) {
		if (noticeVersion == null || !NOTICE_VERSION.matcher(noticeVersion).matches()) {
			throw new IllegalStateException(
					"vibefix.delivery.team-code-notice-version is a version such as 1.0, not '" + noticeVersion + "'");
		}
		this.noticeVersion = noticeVersion;
		this.screenshots = screenshots;
		this.jdbc = jdbc;
		this.jobs = jobs;
		this.criteria = criteria;
		this.states = states;
		this.todos = todos;
		this.deadlines = deadlines;
		this.ids = ids;
		this.clock = clock;
	}

	/**
	 * N-33's current version, which a Code Job's handover must name and records
	 * ({@code vibefix.delivery.team-code-notice-version}).
	 */
	String noticeVersion() {
		return this.noticeVersion;
	}

	/** The first Milestone in force waiting for a handover: Funded or Revision Requested. */
	@Transactional(readOnly = true)
	Optional<MilestoneInForce> waiting(UUID jobId) {
		Map<UUID, String> live = this.states.statesOf(jobId);
		return milestones(jobId).stream()
			.filter(milestone -> HANDABLE.contains(live.getOrDefault(milestone.milestoneId(), "PLANNED")))
			.findFirst();
	}

	/**
	 * The form's preflight for one Milestone in force; a Code Job's checks of its Working Repository come
	 * from {@link CodeHandovers}, read outside the transaction.
	 */
	@Transactional(readOnly = true)
	Preflight preflight(Viewer mentor, UUID milestoneId, CodeHandovers.@Nullable Check code) {
		UUID jobId = mentor.job().jobId();
		MilestoneInForce milestone = milestone(jobId, milestoneId);
		String state = this.states.statesOf(jobId).getOrDefault(milestoneId, "PLANNED");
		List<String> problems = new ArrayList<>();
		if (!HANDABLE.contains(state)) {
			problems.add(DeliveryReason.MILESTONE_NOT_HELD.name());
		}
		if (!mentor.job().assessment() && code != null) {
			problems.addAll(code.problems());
		}
		return new Preflight(milestone, state, mentor.job().version(), mentor.job().assessment(),
				criteriaOf(jobId, milestoneId), problems, code == null ? List.of() : code.sensitive(),
				code == null || code.range() == null ? 0 : code.range().commits().size());
	}

	/**
	 * "Gửi bàn giao", first step: everything the handover will store, checked, before anything is written or
	 * copied. The criterion notes and the no-preview reason are stored as the Mentor wrote them, stripped: nothing
	 * checks, masks or refuses their text (D5). A Code Job's commit is copied on the runner between this and
	 * {@link #record}.
	 * @throws ApiException {@code NOT_FOUND} for a Milestone not in force; {@code GUARD_FAILED
	 * MILESTONE_NOT_HELD}; {@code VALIDATION CRITERION_MARK_MISSING}, {@code TOO_MANY_LINKS}, {@code PREVIEW_OR_RECORDING},
	 * {@code RECORDING_REASON_MISSING}, {@code PREVIEW_REQUIRED}, {@code REPORT_LINK_MISSING},
	 * {@code TOO_MANY_SCREENSHOTS}, or {@code SCREENSHOT_UNKNOWN}; plain {@code VALIDATION} for a link that is not
	 * {@code https} or whose label is blank or too long, or a note longer than {@value #MAX_NOTE} characters
	 */
	@Transactional(readOnly = true)
	Draft draft(Viewer mentor, UUID milestoneId, List<CriterionMark> marks, Evidence evidence) {
		UUID jobId = mentor.job().jobId();
		MilestoneInForce milestone = milestone(jobId, milestoneId);
		if (!HANDABLE.contains(this.states.statesOf(jobId).getOrDefault(milestoneId, "PLANNED"))) {
			throw new ApiException(ErrorCode.GUARD_FAILED, DeliveryReason.MILESTONE_NOT_HELD);
		}
		Map<UUID, CriterionMark> byCriterion = new HashMap<>();
		marks.forEach(mark -> byCriterion.put(mark.criterionId(), mark));
		List<CriterionInForce> criteria = criteriaOf(jobId, milestoneId);
		for (CriterionInForce criterion : criteria) {
			CriterionMark mark = byCriterion.get(criterion.criterionId());
			if (mark == null || mark.note().isBlank()) {
				throw new ApiException(ErrorCode.VALIDATION, DeliveryReason.CRITERION_MARK_MISSING);
			}
		}
		Map<UUID, String> criterionNotes = new HashMap<>();
		for (CriterionInForce criterion : criteria) {
			criterionNotes.put(criterion.criterionId(), note(byCriterion.get(criterion.criterionId()).note()));
		}
		List<Link> links = evidence.links();
		if (links.size() > MAX_LINKS) {
			throw new ApiException(ErrorCode.VALIDATION, DeliveryReason.TOO_MANY_LINKS);
		}
		links.forEach(Handovers::requireWellFormed);
		String preview = blankToNull(evidence.previewUrl());
		String reason = blankToNull(evidence.noPreviewReason());
		if (preview != null && reason != null) {
			throw new ApiException(ErrorCode.VALIDATION, DeliveryReason.PREVIEW_OR_RECORDING);
		}
		// FR-32: "Không thể làm bản xem thử" shows the change another way, as a recording among the links.
		if (reason != null && links.stream().noneMatch(link -> link.kind() == Link.Kind.VIDEO)) {
			throw new ApiException(ErrorCode.VALIDATION, DeliveryReason.RECORDING_REASON_MISSING);
		}
		if (mentor.job().previewRequired() && preview == null && reason == null) {
			throw new ApiException(ErrorCode.VALIDATION, DeliveryReason.PREVIEW_REQUIRED);
		}
		// FR-10: the platform stores no documents, so an Assessment Report reaches the Customer as a document link.
		if (mentor.job().assessment() && links.stream().noneMatch(link -> link.kind() == Link.Kind.DOCUMENT)) {
			throw new ApiException(ErrorCode.VALIDATION, DeliveryReason.REPORT_LINK_MISSING);
		}
		this.screenshots.requireAttachable(jobId, mentor.accountId(), evidence.screenshotIds());
		Map<UUID, Boolean> met = new HashMap<>();
		criteria.forEach(criterion -> met.put(criterion.criterionId(), byCriterion.get(criterion.criterionId()).met()));
		return new Draft(milestone, criteria, met, criterionNotes, preview, reason, links, evidence.screenshotIds());
	}

	/**
	 * A link as {@code delivery.handover_link} takes it and as its reader can trust it: the request's pattern, then
	 * the URL parsed as {@link URI} with scheme {@code https}, a host, and no user info. The pattern's {@code \S}
	 * lets through what the URI refuses -- a backslash, an ideographic space, a control character -- and a URL whose
	 * host is not the one a reader would see, so this refuses those before anything is copied or written. Its text is
	 * never fetched, and stored as written (D5).
	 */
	private static void requireWellFormed(Link link) {
		if (link.kind() == null || link.url() == null || link.label() == null || link.url().length() > MAX_LINK_URL
				|| !LINK_URL.matcher(link.url()).matches() || link.label().isBlank()
				|| link.label().length() > MAX_LINK_LABEL || linkHost(link.url()).isEmpty()) {
			throw new ApiException(ErrorCode.VALIDATION);
		}
	}

	/**
	 * A link's host as it is typed, lowercased ASCII, which the report shows as its domain (AD-35): read from the
	 * URL's text alone, and never turned into Unicode, so a look-alike name shows as the {@code xn--} name it is.
	 * @return empty for a URL that is not {@code https}, has no host, carries user info, or does not parse
	 */
	static Optional<String> linkHost(String url) {
		try {
			URI uri = new URI(url);
			if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null || uri.getRawUserInfo() != null) {
				return Optional.empty();
			}
			return Optional.of(uri.getHost().toLowerCase(Locale.ROOT));
		}
		catch (URISyntaxException refused) {
			return Optional.empty();
		}
	}

	/**
	 * "Gửi bàn giao", last step: M2 on {@code version} with the drafted handover, then its rows; a Code
	 * Job's with its Handover Commit, range, and Sensitive Changes.
	 * @throws ApiException M2's refusals, {@code STALE} among them; nothing is written then
	 */
	@Transactional
	Handover record(Pending pending, @Nullable Code code) {
		UUID jobId = pending.jobId();
		UUID id = pending.id();
		UUID milestoneId = pending.milestoneId();
		Submitted submitted = this.jobs.submit(jobId, milestoneId, pending.mentorId(), pending.version(), id,
				code == null ? null : code.commit());
		TeamCodeConfirmation teamCode = pending.teamCode();
		int round = this.jdbc.sql("SELECT count(*) FROM delivery.handover WHERE milestone_id = :milestone")
			.param("milestone", milestoneId)
			.query(Integer.class)
			.single() + 1;
		Instant now = this.clock.instant();
		this.jdbc.sql("""
				INSERT INTO delivery.handover (id, job_id, product_id, milestone_id, milestone_number, milestone_title, round,
				    mentor_account_id, status, job_version, transition_id, check_by, created_at,
				    handover_commit, range_start, pull_number, unattributed_commits, commits_truncated,
				    sensitive_change_count, preview_url, preview_checked_at, no_preview_reason,
				    team_code_account_id, team_code_notice_version, team_code_confirmed_at)
				VALUES (:id, :job, :product, :milestone, :number, :title, :round, :mentor, 'SUBMITTED', :version, :transition,
				    :checkBy, :now, :commit, :start, :pull, :unattributed, :truncated, :sensitiveCount,
				    :preview, :previewChecked, :reason, :teamCodeAccount, :teamCodeVersion, :teamCodeAt)
				""")
			.param("id", id)
			.param("job", jobId)
			.param("product", pending.productId())
			.param("milestone", milestoneId)
			.param("number", pending.milestoneNumber())
			.param("title", pending.milestoneTitle())
			.param("round", round)
			.param("mentor", pending.mentorId())
			.param("version", submitted.version())
			.param("transition", submitted.transitionId())
			.param("checkBy", Timestamp.from(submitted.checkBy()))
			.param("now", Timestamp.from(now))
			.param("commit", code == null ? null : code.commit())
			.param("start", code == null ? null : code.rangeStart())
			.param("pull", code == null ? null : code.pullNumber())
			.param("unattributed", code == null ? 0 : (int) code.commits().stream().filter(c -> c.authorId() == null).count())
			.param("truncated", code != null && code.truncated())
			.param("sensitiveCount", code == null ? 0 : code.sensitive().size())
			// The caller checked the preview link just before (AD-32): that check is the one stored.
			.param("preview", pending.previewUrl())
			.param("previewChecked", pending.previewUrl() == null ? null : Timestamp.from(now))
			.param("reason", pending.noPreviewReason())
			// N-33 (FR-25, R28): the Mentor's duty confirmation as "Gửi bàn giao" carried it; none for an Assessment Job.
			.param("teamCodeAccount", teamCode == null ? null : teamCode.accountId())
			.param("teamCodeVersion", teamCode == null ? null : teamCode.noticeVersion())
			.param("teamCodeAt", teamCode == null ? null : Timestamp.from(teamCode.confirmedAt()))
			.update();
		this.screenshots.attach(id, jobId, pending.mentorId(), pending.screenshotIds());
		// D3 (AD-32): stored as the Mentor wrote them, in their order, and never requested.
		for (int position = 0; position < pending.links().size(); position++) {
			Link link = pending.links().get(position);
			this.jdbc.sql("""
					INSERT INTO delivery.handover_link (handover_id, position, kind, url, label)
					VALUES (:id, :position, :kind, :url, :label)
					""")
				.param("id", id)
				.param("position", position)
				.param("kind", link.kind().name())
				.param("url", link.url())
				.param("label", link.label())
				.update();
		}
		for (PendingCriterion criterion : pending.criteria()) {
			this.jdbc.sql("""
					INSERT INTO delivery.handover_criterion (handover_id, criterion_id, number, statement, check_steps, met, note)
					VALUES (:id, :criterion, :number, :statement, :steps, :met, :note)
					""")
				.param("id", id)
				.param("criterion", criterion.criterionId())
				.param("number", criterion.number())
				.param("statement", criterion.statement())
				.param("steps", criterion.checkSteps())
				.param("met", criterion.met())
				.param("note", criterion.note())
				.update();
		}
		if (code != null) {
			recordCode(jobId, id, code);
		}
		this.todos.close(HAND_OVER, pending.mentorId(), milestoneId);
		this.todos.open(new TodoRequest(REVIEW_HANDOVER, pending.customerId(), Audience.CUSTOMER, id,
				Map.of("n", String.valueOf(pending.milestoneNumber()), "date", dayMonth(submitted.checkBy())),
				"/cong-viec/" + jobId + "/ban-giao/" + id, submitted.checkBy(), jobId));
		this.deadlines.schedule(new DeadlineRequest(AutoAcceptance.KEY, id, id.toString(), jobId, submitted.checkBy()));
		this.deadlines.schedule(new DeadlineRequest(AcceptanceReminder.KEY, id, "1", jobId, submitted.firstReminderAt()));
		this.deadlines.schedule(new DeadlineRequest(AcceptanceReminder.KEY, id, "2", jobId, submitted.secondReminderAt()));
		return find(id).orElseThrow();
	}

	/** The commits and the Sensitive Changes of a Code handover. */
	private void recordCode(UUID jobId, UUID id, Code code) {
		for (int position = 0; position < code.commits().size(); position++) {
			Commit commit = code.commits().get(position);
			this.jdbc.sql("""
					INSERT INTO delivery.handover_commit (handover_id, position, sha, message, author_github_id)
					VALUES (:id, :position, :sha, :message, :author)
					""")
				.param("id", id)
				.param("position", position)
				.param("sha", commit.sha())
				.param("message", commit.message())
				.param("author", commit.authorId())
				.update();
		}
		// One statement, whatever the run reported: a range of 100,000 paths is thousands of Sensitive Changes.
		List<SensitiveChange> stored = code.sensitive().size() > MAX_SENSITIVE_ROWS
				? code.sensitive().subList(0, MAX_SENSITIVE_ROWS) : code.sensitive();
		if (!stored.isEmpty()) {
			this.jdbc.sql("""
					INSERT INTO delivery.handover_sensitive_change (handover_id, position, path, kind)
					SELECT :id, row.position - 1, row.path, row.kind
					FROM unnest(CAST(:paths AS TEXT[]), CAST(:kinds AS TEXT[])) WITH ORDINALITY AS row(path, kind, position)
					""")
				.param("id", id)
				.param("paths", stored.stream()
					.map(change -> change.path().length() > 1000 ? change.path().substring(0, 1000) : change.path())
					.toArray(String[]::new))
				.param("kinds", stored.stream().map(change -> change.kind().name()).toArray(String[]::new))
				.update();
		}
	}

	@Transactional(readOnly = true)
	Optional<Preview> preview(UUID handoverId) {
		return this.jdbc.sql("""
				SELECT preview_url, preview_checked_at, no_preview_reason FROM delivery.handover WHERE id = :id
				""").param("id", handoverId).query((rs, n) -> {
					Timestamp checked = rs.getTimestamp("preview_checked_at");
					return new Preview(rs.getString("preview_url"), checked == null ? null : checked.toInstant(),
							rs.getString("no_preview_reason"));
				})
			.optional();
	}

	/** The handover's links in the Mentor's order (Story 24.3); none for one that carries none. */
	@Transactional(readOnly = true)
	List<Link> links(UUID handoverId) {
		return this.jdbc.sql("SELECT kind, url, label FROM delivery.handover_link WHERE handover_id = :id ORDER BY position")
			.param("id", handoverId)
			.query((rs, n) -> new Link(Link.Kind.valueOf(rs.getString("kind")), rs.getString("url"), rs.getString("label")))
			.list();
	}

	private static @Nullable String blankToNull(@Nullable String text) {
		return text == null || text.isBlank() ? null : text.strip();
	}

	/** The previous Handover Commit of the Job, whichever Milestone it handed over. */
	@Transactional(readOnly = true)
	Optional<String> lastHandoverCommit(UUID jobId) {
		return this.jdbc.sql("""
				SELECT handover_commit FROM delivery.handover WHERE job_id = :job AND handover_commit IS NOT NULL
				ORDER BY created_at DESC, round DESC LIMIT 1
				""").param("job", jobId).query(String.class).optional();
	}

	@Transactional(readOnly = true)
	Optional<CodeRecord> code(UUID handoverId) {
		return this.jdbc.sql("""
				SELECT handover_commit, pull_number, unattributed_commits, commits_truncated, sensitive_change_count,
				    team_code_confirmed_at
				FROM delivery.handover WHERE id = :id AND handover_commit IS NOT NULL
				""").param("id", handoverId).query((rs, n) -> {
					Timestamp confirmed = rs.getTimestamp("team_code_confirmed_at");
					return new CodeRecord(rs.getString("handover_commit"), rs.getInt("pull_number"),
							rs.getInt("unattributed_commits"), rs.getBoolean("commits_truncated"), commits(handoverId),
							sensitive(handoverId), rs.getInt("sensitive_change_count"),
							confirmed == null ? null : confirmed.toInstant());
				})
			.optional();
	}

	private List<RecordedCommit> commits(UUID handoverId) {
		return this.jdbc.sql("""
				SELECT sha, message FROM delivery.handover_commit WHERE handover_id = :id ORDER BY position
				""")
			.param("id", handoverId)
			.query((rs, n) -> new RecordedCommit(rs.getString("sha"), rs.getString("message")))
			.list();
	}

	private List<SensitiveChange> sensitive(UUID handoverId) {
		return this.jdbc.sql("SELECT path, kind FROM delivery.handover_sensitive_change WHERE handover_id = :id ORDER BY position")
			.param("id", handoverId)
			.query((rs, n) -> new SensitiveChange(rs.getString("path"), SensitiveChanges.Kind.valueOf(rs.getString("kind"))))
			.list();
	}

	/** {@code dd/MM} in Vietnam time, as the screens and the strip show a date. */
	static String dayMonth(Instant at) {
		return DateTimeFormatter.ofPattern("dd/MM").format(at.atZone(ZoneId.of("Asia/Ho_Chi_Minh")));
	}

	/** The Job's handovers, oldest first. */
	@Transactional(readOnly = true)
	List<Handover> of(UUID jobId) {
		return this.jdbc.sql("SELECT " + COLUMNS + " FROM delivery.handover WHERE job_id = :job ORDER BY seq")
			.param("job", jobId)
			.query(Handovers::handover)
			.list();
	}

	@Transactional(readOnly = true)
	Optional<Handover> find(UUID id) {
		return this.jdbc.sql("SELECT " + COLUMNS + " FROM delivery.handover WHERE id = :id")
			.param("id", id)
			.query(Handovers::handover)
			.optional();
	}

	@Transactional(readOnly = true)
	List<Criterion> criteria(UUID handoverId) {
		return this.jdbc.sql("""
				SELECT criterion_id, number, statement, check_steps, met, note FROM delivery.handover_criterion
				WHERE handover_id = :id ORDER BY number
				""")
			.param("id", handoverId)
			.query((rs, n) -> new Criterion(rs.getObject("criterion_id", UUID.class), rs.getInt("number"),
					rs.getString("statement"), rs.getString("check_steps"), rs.getBoolean("met"), rs.getString("note")))
			.list();
	}

	List<MilestoneInForce> milestones(UUID jobId) {
		return this.criteria.inForce(jobId).map(inForce -> inForce.milestones()).orElse(List.of());
	}

	private MilestoneInForce milestone(UUID jobId, UUID milestoneId) {
		return milestones(jobId).stream()
			.filter(found -> found.milestoneId().equals(milestoneId))
			.findFirst()
			.orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
	}

	private List<CriterionInForce> criteriaOf(UUID jobId, UUID milestoneId) {
		return this.criteria.inForce(jobId)
			.map(inForce -> inForce.criteria().stream().filter(c -> c.milestoneId().equals(milestoneId)).toList())
			.orElse(List.of());
	}

	/**
	 * A note or comment stripped, and otherwise as written (D5): nothing checks, masks or refuses its text.
	 * @throws ApiException {@code VALIDATION} past {@value #MAX_NOTE} characters
	 */
	static String note(String text) {
		String stripped = text.strip();
		if (stripped.length() > MAX_NOTE) {
			throw new ApiException(ErrorCode.VALIDATION);
		}
		return stripped;
	}

	private static Handover handover(ResultSet rs, int n) throws SQLException {
		Timestamp decided = rs.getTimestamp("decided_at");
		return new Handover(rs.getObject("id", UUID.class), rs.getObject("job_id", UUID.class),
				rs.getObject("product_id", UUID.class), rs.getObject("milestone_id", UUID.class),
				rs.getInt("milestone_number"), rs.getString("milestone_title"), rs.getInt("round"),
				rs.getObject("mentor_account_id", UUID.class), rs.getString("status"), rs.getLong("job_version"),
				Objects.requireNonNull(rs.getTimestamp("check_by")).toInstant(), rs.getTimestamp("created_at").toInstant(),
				decided == null ? null : decided.toInstant(), rs.getBoolean("auto"));
	}

}
