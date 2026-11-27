package vn.vibefix.job.internal;

import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.jspecify.annotations.Nullable;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import tools.jackson.databind.json.JsonMapper;
import vn.vibefix.audit.AuditActor;
import vn.vibefix.audit.AuditEntry;
import vn.vibefix.audit.AuditLog;
import vn.vibefix.audit.AuditObject;
import vn.vibefix.job.AgreementConfirmed;
import vn.vibefix.job.AgreementConfirmed.ConfirmedMilestone;
import vn.vibefix.job.internal.Agreements.Content;
import vn.vibefix.job.internal.Agreements.Criterion;
import vn.vibefix.job.internal.Agreements.ExternalAccess;
import vn.vibefix.job.internal.Agreements.Milestone;
import vn.vibefix.job.internal.Agreements.Version;
import vn.vibefix.job.internal.CrewService.Row;
import vn.vibefix.job.internal.Jobs.Job;
import vn.vibefix.notification.Audience;
import vn.vibefix.notification.NotificationRequest;
import vn.vibefix.notification.Notifications;
import vn.vibefix.notification.Recipient;
import vn.vibefix.notification.TodoRequest;
import vn.vibefix.notification.Todos;
import vn.vibefix.shared.ApiException;
import vn.vibefix.shared.ErrorCode;
import vn.vibefix.shared.IdGenerator;

/**
 * JOB-6's review and confirmation (FR-16, FR-17, AD-29, AD-41 as revised by R-1): the Customer
 * comments on the latest shared draft, asks for changes, or approves it, each decision at the
 * {@code Job.version} the screen loaded; the Mentor then confirms, at the {@code Job.version} and the
 * {@code crewVersion} the screen loaded. The confirmation waits for no Crew condition (FR-17, R7, Story 21.5): it
 * writes the insert-only Agreement version 1 with the GitHub usernames of the members {@code ACTIVE} then, and the Crew
 * keeps growing from Crew Applications; J6 needs one signed member.
 */
@Component
class AgreementReviews {

	static final String REVISE_TODO = Agreements.REVISE_TODO;

	static final String CONFIRM_TODO = "job.confirm_agreement_mentor";

	static final String CHANGES_REQUESTED = "job.agreement_changes_requested";

	static final String AWAITING_CONFIRMATION = "job.agreement_awaiting_confirmation";

	static final String CONFIRMED = "job.agreement_confirmed";

	static final String AWAITING_FUNDING = "job.milestone_awaiting_funding";

	static final int MAX_COMMENTS = 200;

	/** The confirmation notice's Crew when no member has signed yet (R7): the Crew keeps growing after it. */
	static final String NO_MEMBER_YET = "chưa có thành viên";

	/** What a comment is about; the scope has no entity ID. */
	enum TargetKind {
		SCOPE, MILESTONE, CRITERION, EXTERNAL_ACCESS
	}

	record Comment(UUID id, int versionNo, TargetKind targetKind, @Nullable UUID targetId, String body, Instant createdAt) {
	}

	/** An open change request: the Customer's note and, when they reordered, the Milestone order. */
	record ChangeRequest(UUID id, int versionNo, String note, @Nullable List<UUID> milestoneOrder, Instant createdAt) {
	}

	record Approval(int versionNo, boolean freezeCommitted, List<UUID> liveAccessIds, Instant approvedAt) {
	}

	/** The confirmed Agreement: who confirmed when, the usernames of that moment, never a share. */
	record Confirmed(int versionNo, int draftVersionNo, List<String> crew, List<UUID> liveAccessIds, boolean freezeCommitted,
			Instant approvedAt, Instant confirmedAt, Content content) {
	}

	private static final JsonMapper JSON = JsonMapper.builder().build();

	private final JdbcClient jdbc;

	private final AgreementReads reads;

	private final Agreements agreements;

	private final CrewService crews;

	private final Notifications notifications;

	private final Todos todos;

	private final AuditLog auditLog;

	private final IdGenerator ids;

	private final Clock clock;

	private final ApplicationEventPublisher events;

	AgreementReviews(JdbcClient jdbc, Agreements agreements, CrewService crews, Notifications notifications, Todos todos,
			AuditLog auditLog, IdGenerator ids, Clock clock, ApplicationEventPublisher events, AgreementReads reads) {
		this.reads = reads;
		this.events = events;
		this.jdbc = jdbc;
		this.agreements = agreements;
		this.crews = crews;
		this.notifications = notifications;
		this.todos = todos;
		this.auditLog = auditLog;
		this.ids = ids;
		this.clock = clock;
	}

	// ---- The Customer's decisions ---------------------------------------------------------

	/**
	 * "Góp ý" on the scope or one entity of the latest shared version.
	 * @throws ApiException {@code STALE} for another version or a confirmed Agreement; {@code VALIDATION}
	 * for a target not in the version, or past {@value #MAX_COMMENTS} comments
	 */
	@Transactional
	Comment comment(UUID customerId, UUID jobId, int versionNo, TargetKind kind, @Nullable UUID targetId, String body) {
		Job job = lockCustomerJob(customerId, jobId);
		Version latest = latest(job);
		requireReviewable(job, latest, versionNo);
		if (!targets(latest.content(), kind).contains(targetId == null ? SCOPE_TARGET : targetId)
				|| (kind == TargetKind.SCOPE) != (targetId == null)) {
			throw new ApiException(ErrorCode.VALIDATION);
		}
		long count = this.jdbc.sql("SELECT count(*) FROM job.agreement_comment WHERE job_id = :job AND version_no = :no")
			.param("job", jobId)
			.param("no", versionNo)
			.query(Long.class)
			.single();
		if (count >= MAX_COMMENTS) {
			throw new ApiException(ErrorCode.VALIDATION);
		}
		Comment comment = new Comment(this.ids.newId(), versionNo, kind, targetId, body.strip(), this.clock.instant());
		this.jdbc.sql("""
				INSERT INTO job.agreement_comment (id, job_id, version_no, target_kind, target_id, author_account_id, body, created_at)
				VALUES (:id, :job, :no, :kind, :target, :author, :body, :now)
				""")
			.param("id", comment.id())
			.param("job", jobId)
			.param("no", versionNo)
			.param("kind", kind.name())
			.param("target", targetId)
			.param("author", customerId)
			.param("body", comment.body())
			.param("now", Timestamp.from(comment.createdAt()))
			.update();
		return comment;
	}

	/**
	 * "Yêu cầu sửa" (FR-16): the Mentor is told and revises; a new order must be a permutation of the
	 * version's Milestones.
	 * @throws ApiException {@code STALE}; {@code GUARD_FAILED AGREEMENT_APPROVED}; {@code VALIDATION}
	 */
	@Transactional
	void requestChanges(UUID customerId, UUID jobId, long version, int versionNo, String note,
			@Nullable List<UUID> milestoneOrder) {
		Job job = lockCustomerJob(customerId, jobId);
		Version latest = latest(job);
		requireDecision(job, latest, version, versionNo);
		if (approval(jobId, versionNo).isPresent()) {
			throw new ApiException(ErrorCode.GUARD_FAILED, JobReason.AGREEMENT_APPROVED);
		}
		List<UUID> current = latest.content().milestones().stream().map(Milestone::id).toList();
		List<UUID> order = null;
		if (milestoneOrder != null) {
			if (milestoneOrder.size() != current.size() || !new HashSet<>(milestoneOrder).equals(new HashSet<>(current))) {
				throw new ApiException(ErrorCode.VALIDATION);
			}
			order = milestoneOrder.equals(current) ? null : List.copyOf(milestoneOrder);
		}
		UUID id = this.ids.newId();
		Instant now = this.clock.instant();
		this.jdbc.sql("""
				INSERT INTO job.agreement_change_request (id, job_id, version_no, customer_account_id, note, milestone_order, created_at)
				VALUES (:id, :job, :no, :customer, :note, CAST(:order AS UUID[]), :now)
				""")
			.param("id", id)
			.param("job", jobId)
			.param("no", versionNo)
			.param("customer", customerId)
			.param("note", note.strip())
			.param("order", order == null ? null : order.toArray(UUID[]::new))
			.param("now", Timestamp.from(now))
			.update();
		bumpVersion(jobId, now);
		this.auditLog.record(AuditEntry.of(AuditActor.user(customerId), "job.agreement_changes_requested",
				AuditObject.of("job.job", jobId)).forJob(jobId));
		this.todos.closeAll(Agreements.REVIEW_TODO, jobId);
		UUID mentor = Objects.requireNonNull(job.mentorId());
		this.notifications.send(new NotificationRequest(CHANGES_REQUESTED, id, List.of(new Recipient(mentor, Audience.MENTOR)),
				Map.of("job", job.title()), Agreements.agreementLink(jobId), jobId, null));
		this.todos.open(new TodoRequest(REVISE_TODO, mentor, Audience.MENTOR, jobId, Map.of("job", job.title()),
				Agreements.agreementLink(jobId), agreementDue(jobId), jobId));
	}

	/**
	 * "Duyệt thỏa thuận" (FR-17): a Code Job needs N-6; the live-system choices are recorded; the Mentor
	 * is asked to confirm.
	 * @throws ApiException {@code STALE}; {@code GUARD_FAILED AGREEMENT_APPROVED} or
	 * {@code CHANGES_REQUESTED}; {@code VALIDATION FREEZE_COMMITMENT_REQUIRED}, or for an ID that is
	 * not one of the version's External Access entries
	 */
	@Transactional
	void approve(UUID customerId, UUID jobId, long version, int versionNo, boolean freezeCommitment, List<UUID> liveAccessIds) {
		Job job = lockCustomerJob(customerId, jobId);
		Version latest = latest(job);
		requireDecision(job, latest, version, versionNo);
		if (approval(jobId, versionNo).isPresent()) {
			throw new ApiException(ErrorCode.GUARD_FAILED, JobReason.AGREEMENT_APPROVED);
		}
		if (openChangeRequest(jobId).filter(request -> request.versionNo() == versionNo).isPresent()) {
			throw new ApiException(ErrorCode.GUARD_FAILED, JobReason.CHANGES_REQUESTED);
		}
		if (job.code() && !freezeCommitment) {
			throw new ApiException(ErrorCode.VALIDATION, JobReason.FREEZE_COMMITMENT_REQUIRED);
		}
		Set<UUID> accesses = new HashSet<>(targets(latest.content(), TargetKind.EXTERNAL_ACCESS));
		List<UUID> live = liveAccessIds.stream().distinct().toList();
		if (!accesses.containsAll(live)) {
			throw new ApiException(ErrorCode.VALIDATION);
		}
		Instant now = this.clock.instant();
		this.jdbc.sql("""
				INSERT INTO job.agreement_approval (job_id, version_no, customer_account_id, freeze_committed, live_access_ids, approved_at)
				VALUES (:job, :no, :customer, :freeze, CAST(:live AS UUID[]), :now)
				""")
			.param("job", jobId)
			.param("no", versionNo)
			.param("customer", customerId)
			.param("freeze", job.code() && freezeCommitment)
			.param("live", live.toArray(UUID[]::new))
			.param("now", Timestamp.from(now))
			.update();
		bumpVersion(jobId, now);
		this.auditLog.record(AuditEntry.of(AuditActor.user(customerId), "job.agreement_approved", AuditObject.of("job.job", jobId))
			.forJob(jobId));
		this.todos.closeAll(Agreements.REVIEW_TODO, jobId);
		UUID mentor = Objects.requireNonNull(job.mentorId());
		this.notifications.send(new NotificationRequest(AWAITING_CONFIRMATION, cause(jobId, "approved:" + versionNo),
				List.of(new Recipient(mentor, Audience.MENTOR)), Map.of("job", job.title()), Agreements.agreementLink(jobId), jobId,
				null));
		this.todos.open(new TodoRequest(CONFIRM_TODO, mentor, Audience.MENTOR, jobId, Map.of("job", job.title()),
				Agreements.agreementLink(jobId), agreementDue(jobId), jobId));
	}

	// ---- The Mentor's confirmation ---------------------------------------------------------

	/**
	 * "Xác nhận thỏa thuận" (FR-17): under the Job row lock, the approved latest version becomes the
	 * confirmed Agreement version 1 with the GitHub usernames of the members {@code ACTIVE} at this moment, none
	 * included (R7).
	 * <p>
	 * In a Self-mentored Job (FR-85, AD-47 exception 3, B-11) this is the one step: the Customer, who is also the Mentor,
	 * confirms the live draft at the revision the screen loaded, with the Freeze Commitment for a Code Job and the
	 * External Access entries marked as live systems, and nothing was shared or approved before it.
	 * @param alone what the one step carries in a Self-mentored Job; a normal Job reads none of it
	 * @throws ApiException {@code STALE} past Agreement, for a moved {@code Job.version} or
	 * {@code crewVersion}, or once confirmed; {@code GUARD_FAILED AGREEMENT_NOT_APPROVED}, or
	 * {@code DRAFT_CHANGED} when the live draft differs from the approved version. In a Self-mentored Job:
	 * {@code STALE} for another draft revision too; {@code VALIDATION} without a revision, with
	 * {@code AGREEMENT_INCOMPLETE} and its {@code problems}, with {@code FREEZE_COMMITMENT_REQUIRED} for a Code Job, or
	 * for an ID that is not one of the draft's External Access entries
	 */
	@Transactional
	Confirmed confirm(UUID mentorId, UUID jobId, long version, long crewVersion, Alone alone) {
		Job job = lockJob(jobId);
		if (!mentorId.equals(job.mentorId())) {
			throw new ApiException(ErrorCode.NOT_FOUND);
		}
		if (job.state() != JobState.AGREEMENT || job.version() != version || this.crews.crewVersion(jobId) != crewVersion
				|| job.agreementVersionNo() != null) {
			throw new ApiException(ErrorCode.STALE);
		}
		if (job.selfMentored()) {
			return confirmAlone(job, alone);
		}
		Version latest = this.agreements.version(jobId, null)
			.orElseThrow(() -> new ApiException(ErrorCode.GUARD_FAILED, JobReason.AGREEMENT_NOT_APPROVED));
		Approval approval = approval(jobId, latest.versionNo())
			.orElseThrow(() -> new ApiException(ErrorCode.GUARD_FAILED, JobReason.AGREEMENT_NOT_APPROVED));
		if (!draftMatchesShared(job, latest)) {
			throw new ApiException(ErrorCode.GUARD_FAILED, JobReason.DRAFT_CHANGED);
		}
		Instant now = this.clock.instant();
		this.jdbc.sql("""
				INSERT INTO job.confirmed_agreement (job_id, version_no, draft_version_no, content, crew_usernames, live_access_ids,
				    freeze_committed, customer_account_id, approved_at, mentor_account_id, confirmed_at)
				SELECT :job, 1, :draft, CAST(:content AS JSONB), CAST(:crew AS TEXT[]), a.live_access_ids, a.freeze_committed,
				       a.customer_account_id, a.approved_at, :mentor, :now
				FROM job.agreement_approval a WHERE a.job_id = :job AND a.version_no = :draft
				""")
			.param("job", jobId)
			.param("draft", latest.versionNo())
			.param("content", JSON.writeValueAsString(latest.content()))
			.param("crew", this.crews.activeUsernames(jobId).toArray(String[]::new))
			.param("mentor", mentorId)
			.param("now", Timestamp.from(now))
			.update();
		List<String> usernames = confirmed(job, latest.content(), now);
		// Story 25.1b: version 1 says what the approved draft said, so its Crew readers have read it and are told nothing.
		this.reads.confirmedFromDraft(jobId, latest.versionNo(), 1);
		return new Confirmed(1, latest.versionNo(), usernames, approval.liveAccessIds(), approval.freezeCommitted(),
				approval.approvedAt(), now, latest.content());
	}

	/**
	 * What the one-step confirmation of a Self-mentored Job carries (AD-47, B-11).
	 * @param revision the draft revision the screen loaded (AD-23)
	 * @param freezeCommitment N-6, which a Code Job needs
	 * @param liveAccessIds the External Access entries the Customer marks as live systems
	 */
	record Alone(@Nullable Integer revision, boolean freezeCommitment, List<UUID> liveAccessIds) {

		Alone {
			liveAccessIds = List.copyOf(liveAccessIds);
		}

	}

	/**
	 * The one step of a Self-mentored Job, under the Job row lock {@link #confirm} took: the live draft becomes the
	 * confirmed Agreement version 1. The draft is kept as a version of its own kind ({@code CONFIRMED_ALONE}), which the
	 * confirmed Agreement names; no approval row is written, and the audit records the one actor once.
	 */
	private Confirmed confirmAlone(Job job, Alone alone) {
		UUID jobId = job.id();
		if (alone.revision() == null) {
			throw new ApiException(ErrorCode.VALIDATION);
		}
		Agreements.Draft draft = this.agreements.draft(job).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
		if (draft.revision() != alone.revision()) {
			throw new ApiException(ErrorCode.STALE);
		}
		Content content = draft.content();
		List<Agreements.Problem> problems = this.agreements.problems(content);
		if (!problems.isEmpty()) {
			throw new ApiException(ErrorCode.VALIDATION, JobReason.AGREEMENT_INCOMPLETE, Map.of("problems", problems));
		}
		if (job.code() && !alone.freezeCommitment()) {
			throw new ApiException(ErrorCode.VALIDATION, JobReason.FREEZE_COMMITMENT_REQUIRED);
		}
		List<UUID> live = alone.liveAccessIds().stream().distinct().toList();
		if (!new HashSet<>(targets(content, TargetKind.EXTERNAL_ACCESS)).containsAll(live)) {
			throw new ApiException(ErrorCode.VALIDATION);
		}
		boolean freeze = job.code() && alone.freezeCommitment();
		Instant now = this.clock.instant();
		int draftNo = this.jdbc.sql("SELECT coalesce(max(version_no), 0) + 1 FROM job.agreement_version WHERE job_id = :job")
			.param("job", jobId)
			.query(Integer.class)
			.single();
		this.jdbc.sql("""
				INSERT INTO job.agreement_version (job_id, version_no, status, content, author_account_id, created_at)
				VALUES (:job, :no, 'CONFIRMED_ALONE', CAST(:content AS JSONB), :author, :now)
				""")
			.param("job", jobId)
			.param("no", draftNo)
			.param("content", JSON.writeValueAsString(content))
			.param("author", job.customerId())
			.param("now", Timestamp.from(now))
			.update();
		this.jdbc.sql("""
				INSERT INTO job.confirmed_agreement (job_id, version_no, draft_version_no, content, crew_usernames, live_access_ids,
				    freeze_committed, customer_account_id, approved_at, mentor_account_id, confirmed_at)
				VALUES (:job, 1, :draft, CAST(:content AS JSONB), CAST(:crew AS TEXT[]), CAST(:live AS UUID[]), :freeze,
				    :customer, :now, :customer, :now)
				""")
			.param("job", jobId)
			.param("draft", draftNo)
			.param("content", JSON.writeValueAsString(content))
			.param("crew", this.crews.activeUsernames(jobId).toArray(String[]::new))
			.param("live", live.toArray(UUID[]::new))
			.param("freeze", freeze)
			.param("customer", job.customerId())
			.param("now", Timestamp.from(now))
			.update();
		List<String> usernames = confirmed(job, content, now);
		// Whoever read the revision that was confirmed has read version 1; a reader behind it is told (AD-41, R67).
		this.reads.confirmedFromDraft(jobId, draft.revision(), 1);
		this.reads.newer(jobId, job.title(), new AgreementReads.Mark(true, 1));
		return new Confirmed(1, draftNo, usernames, live, freeze, now, now, content);
	}

	/**
	 * What follows the confirmed Agreement version 1 in every Job: the Job names it, the audit records the confirmer, the
	 * Agreement to-dos close and the External Access ones open, everyone is told, Milestone 1 asks for its funding once a
	 * member has signed, and {@link AgreementConfirmed} goes out. In a Self-mentored Job the Customer and the Mentor are
	 * one account, told once, with the Customer's text (AD-47 exception 12).
	 * @return the GitHub usernames of the members {@code ACTIVE} at this moment
	 */
	private List<String> confirmed(Job job, Content content, Instant now) {
		UUID jobId = job.id();
		UUID mentorId = Objects.requireNonNull(job.mentorId());
		List<Row> active = this.crews.rows(jobId).stream().filter(Row::active).toList();
		List<String> usernames = this.crews.activeUsernames(jobId);
		this.jdbc.sql("UPDATE job.job SET agreement_version_no = 1, version = version + 1, updated_at = :now WHERE id = :id")
			.param("id", jobId)
			.param("now", Timestamp.from(now))
			.update();
		this.auditLog.record(AuditEntry.of(AuditActor.user(mentorId), "job.agreement_confirmed", AuditObject.of("job.job", jobId))
			.forJob(jobId));
		for (String key : List.of(CONFIRM_TODO, REVISE_TODO, Agreements.REVIEW_TODO, Agreements.DRAFT_TODO)) {
			this.todos.closeAll(key, jobId);
		}
		// FR-66: N-8's guidance, one to-do per External Access item, which the Customer's tick closes.
		for (ExternalAccess access : content.externalAccess()) {
			this.todos.open(new TodoRequest(ExternalAccessGrants.TODO, job.customerId(), Audience.CUSTOMER, access.id(),
					Map.of("system", access.system()), Agreements.agreementLink(jobId), null, jobId));
		}
		List<Recipient> everyone = new ArrayList<>(List.of(new Recipient(job.customerId(), Audience.CUSTOMER)));
		if (!job.selfMentored()) {
			everyone.add(new Recipient(mentorId, Audience.MENTOR));
		}
		active.forEach(row -> everyone.add(new Recipient(row.studentId(), Audience.CREW_ACTIVE)));
		this.notifications.send(new NotificationRequest(CONFIRMED, cause(jobId, "confirmed:1"), everyone,
				Map.of("job", job.title(), "crew", usernames.isEmpty() ? NO_MEMBER_YET : String.join(" · ", usernames)),
				Agreements.agreementLink(jobId), jobId, null));
		if (!this.crews.countedActive(jobId).isEmpty()) {
			askToFundMilestoneOne(job, content);
		}
		List<ConfirmedMilestone> milestones = new ArrayList<>();
		for (int m = 0; m < content.milestones().size(); m++) {
			Milestone milestone = content.milestones().get(m);
			milestones.add(new ConfirmedMilestone(milestone.id(), m + 1, milestone.title(),
					milestone.priceVnd() == null ? 0 : milestone.priceVnd()));
		}
		this.events.publishEvent(new AgreementConfirmed(this.ids.newId(), jobId, job.productId(), job.customerId(), job.title(), 1, milestones));
		return usernames;
	}

	/**
	 * R7: the Customer is asked to pay Milestone 1 once the Agreement is confirmed with at least one signed member, at the
	 * confirmation or at the first signature after it. One cause, so a later signature or a replay adds nothing.
	 */
	void askToFundMilestoneOne(Job job, Content content) {
		content.milestones().stream().findFirst().ifPresent(first -> this.notifications.send(new NotificationRequest(
				AWAITING_FUNDING, cause(job.id(), "funding:" + first.id()),
				List.of(new Recipient(job.customerId(), Audience.CUSTOMER)),
				Map.of("job", job.title(), "n", "1", "amount", vnd(first.priceVnd() == null ? 0 : first.priceVnd())),
				"/cong-viec/" + job.id(), job.id(), null)));
	}

	/**
	 * Whether the live draft still says what the latest shared version says: the confirmed Agreement
	 * references the live entities (AD-29), so an edit saved after the share must be shared first.
	 */
	boolean draftMatchesShared(Job job, Version latest) {
		return this.agreements.draft(job).map(draft -> {
			Content live = draft.content();
			Content shared = latest.content();
			return live.scope().equals(shared.scope()) && Objects.equals(live.priceVnd(), shared.priceVnd())
					&& live.skills().stream().map(Agreements.SkillName::id).toList()
						.equals(shared.skills().stream().map(Agreements.SkillName::id).toList())
					&& live.milestones().equals(shared.milestones()) && live.externalAccess().equals(shared.externalAccess());
		}).orElse(false);
	}

	// ---- Reads --------------------------------------------------------------------------------

	/** A version's comments, oldest first. */
	@Transactional(readOnly = true)
	List<Comment> comments(UUID jobId, int versionNo) {
		return this.jdbc.sql("""
				SELECT id, version_no, target_kind, target_id, body, created_at FROM job.agreement_comment
				WHERE job_id = :job AND version_no = :no ORDER BY created_at, id
				""")
			.param("job", jobId)
			.param("no", versionNo)
			.query((rs, n) -> new Comment(rs.getObject("id", UUID.class), rs.getInt("version_no"),
					TargetKind.valueOf(rs.getString("target_kind")), rs.getObject("target_id", UUID.class), rs.getString("body"),
					rs.getTimestamp("created_at").toInstant()))
			.list();
	}

	/** The latest change request that no share has resolved yet. */
	@Transactional(readOnly = true)
	Optional<ChangeRequest> openChangeRequest(UUID jobId) {
		return this.jdbc.sql("""
				SELECT id, version_no, note, milestone_order, created_at FROM job.agreement_change_request
				WHERE job_id = :job AND resolved_at IS NULL ORDER BY created_at DESC, id DESC LIMIT 1
				""")
			.param("job", jobId)
			.query((rs, n) -> new ChangeRequest(rs.getObject("id", UUID.class), rs.getInt("version_no"), rs.getString("note"),
					rs.getArray("milestone_order") == null ? null : uuids(rs.getArray("milestone_order")),
					rs.getTimestamp("created_at").toInstant()))
			.optional();
	}

	@Transactional(readOnly = true)
	Optional<Approval> approval(UUID jobId, int versionNo) {
		return this.jdbc.sql("""
				SELECT version_no, freeze_committed, live_access_ids, approved_at FROM job.agreement_approval
				WHERE job_id = :job AND version_no = :no
				""")
			.param("job", jobId)
			.param("no", versionNo)
			.query((rs, n) -> new Approval(rs.getInt("version_no"), rs.getBoolean("freeze_committed"),
					uuids(rs.getArray("live_access_ids")), rs.getTimestamp("approved_at").toInstant()))
			.optional();
	}

	/** A stored version's content. */
	static Content content(String json) {
		return JSON.readValue(json, Content.class);
	}

	private static final String CONFIRMED_COLUMNS = """
			version_no, draft_version_no, crew_usernames, live_access_ids, freeze_committed, approved_at, confirmed_at,
			content::text AS content""";

	/** The confirmed Agreement, once the Mentor confirmed: its latest version. */
	@Transactional(readOnly = true)
	Optional<Confirmed> confirmed(UUID jobId) {
		return this.jdbc
			.sql("SELECT " + CONFIRMED_COLUMNS + " FROM job.confirmed_agreement WHERE job_id = :job ORDER BY version_no DESC LIMIT 1")
			.param("job", jobId)
			.query(AgreementReviews::confirmedRow)
			.optional();
	}

	/** One confirmed version as it was confirmed, whatever came after it (AD-29). */
	@Transactional(readOnly = true)
	Optional<Confirmed> confirmed(UUID jobId, int versionNo) {
		return this.jdbc
			.sql("SELECT " + CONFIRMED_COLUMNS + " FROM job.confirmed_agreement WHERE job_id = :job AND version_no = :no")
			.param("job", jobId)
			.param("no", versionNo)
			.query(AgreementReviews::confirmedRow)
			.optional();
	}

	private static Confirmed confirmedRow(ResultSet rs, int n) throws SQLException {
		return new Confirmed(rs.getInt("version_no"), rs.getInt("draft_version_no"),
				Arrays.stream((Object[]) rs.getArray("crew_usernames").getArray()).map(String.class::cast).toList(),
				uuids(rs.getArray("live_access_ids")), rs.getBoolean("freeze_committed"),
				rs.getTimestamp("approved_at").toInstant(), rs.getTimestamp("confirmed_at").toInstant(),
				JSON.readValue(rs.getString("content"), Content.class));
	}

	// ---- Helpers ------------------------------------------------------------------------------

	private static final UUID SCOPE_TARGET = new UUID(0, 0);

	/** The IDs a comment of this kind may name; the scope stands for itself. */
	private static List<UUID> targets(Content content, TargetKind kind) {
		return switch (kind) {
			case SCOPE -> List.of(SCOPE_TARGET);
			case MILESTONE -> content.milestones().stream().map(Milestone::id).toList();
			case CRITERION -> content.milestones().stream().flatMap(milestone -> milestone.criteria().stream()).map(Criterion::id).toList();
			case EXTERNAL_ACCESS -> content.externalAccess().stream().map(ExternalAccess::id).toList();
		};
	}

	private Version latest(Job job) {
		return this.agreements.version(job.id(), null).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
	}

	/** Review runs on the latest shared version, in Agreement, until the Mentor confirms. */
	private static void requireReviewable(Job job, Version latest, int versionNo) {
		if (job.state() != JobState.AGREEMENT || job.agreementVersionNo() != null || latest.versionNo() != versionNo) {
			throw new ApiException(ErrorCode.STALE);
		}
	}

	/** A Customer decision also carries the {@code Job.version} the screen loaded (AD-23). */
	private static void requireDecision(Job job, Version latest, long version, int versionNo) {
		requireReviewable(job, latest, versionNo);
		if (job.version() != version) {
			throw new ApiException(ErrorCode.STALE);
		}
	}

	/**
	 * The Customer's Job, locked for a review decision. A Self-mentored Job has no review (AD-47, B-11): commenting,
	 * asking for changes and approving find nothing there, whatever the caller is.
	 */
	private Job lockCustomerJob(UUID customerId, UUID jobId) {
		Job job = lockJob(jobId);
		if (!customerId.equals(job.customerId()) || job.selfMentored()) {
			throw new ApiException(ErrorCode.NOT_FOUND);
		}
		return job;
	}

	private Job lockJob(UUID jobId) {
		return this.jdbc.sql("SELECT " + Jobs.COLUMNS + " FROM job.job WHERE id = :id AND " + Jobs.LIVE + " FOR UPDATE")
			.param("id", jobId)
			.query(Jobs::job)
			.optional()
			.orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
	}

	private void bumpVersion(UUID jobId, Instant now) {
		this.jdbc.sql("UPDATE job.job SET version = version + 1, updated_at = :now WHERE id = :id")
			.param("id", jobId)
			.param("now", Timestamp.from(now))
			.update();
	}

	private @Nullable Instant agreementDue(UUID jobId) {
		return this.jdbc.sql("SELECT agreement_due_at FROM job.job WHERE id = :id")
			.param("id", jobId)
			.query(Timestamp.class)
			.optional()
			.map(Timestamp::toInstant)
			.orElse(null);
	}

	private static UUID cause(UUID jobId, String fact) {
		return UUID.nameUUIDFromBytes((jobId + ":" + fact).getBytes(java.nio.charset.StandardCharsets.UTF_8));
	}

	/** "2.000.000 VND", as UX-DR20 writes whole VND. */
	static String vnd(long amount) {
		return String.format(Locale.ROOT, "%,d", amount).replace(',', '.') + " VND";
	}

	private static List<UUID> uuids(Array array) throws SQLException {
		return Arrays.stream((Object[]) array.getArray()).map(UUID.class::cast).toList();
	}

}
