package vn.vibefix.identity.internal;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.jspecify.annotations.Nullable;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import vn.vibefix.audit.AuditActor;
import vn.vibefix.audit.AuditEntry;
import vn.vibefix.audit.AuditLog;
import vn.vibefix.audit.AuditObject;
import vn.vibefix.catalog.Catalog;
import vn.vibefix.catalog.Skill;
import vn.vibefix.identity.MentorApprovalStatus;
import vn.vibefix.identity.MentorApproved;
import vn.vibefix.notification.Audience;
import vn.vibefix.notification.NotificationRequest;
import vn.vibefix.notification.Notifications;
import vn.vibefix.notification.Recipient;
import vn.vibefix.shared.ApiException;
import vn.vibefix.shared.ErrorCode;
import vn.vibefix.shared.IdGenerator;
import vn.vibefix.timers.DeadlineRequest;
import vn.vibefix.timers.Deadlines;

/**
 * ADM-13 (FR-74, AD-19, SG-10): the queue of Mentor applications, each with the accounts that may be
 * the same person, and the decision after a step-up. An approval publishes {@link MentorApproved}; a
 * rejection carries a reason and a 30-day wait before the Mentor may submit again, after which the
 * LinkedIn profile is free; its fields are deleted 12 months later, and the decision stays. Either
 * way the document is deleted 30 days after the decision.
 * <p>
 * Decisions lock the Mentor's account row, as the Mentor's own saves do, and return a step-up
 * refusal rather than throw it, so a wrong code is counted: the caller refuses after commit.
 */
@Component
class MentorDecisions {

	static final String APPROVED_NOTIFICATION = "identity.mentor_approved";

	static final String REJECTED_NOTIFICATION = "identity.mentor_rejected";

	/** The wait before a rejected Mentor may submit again (FR-74). */
	static final Duration REAPPLY_WAIT = Duration.ofDays(30);

	/** How long a decided application's document is kept (FR-74). */
	static final Duration DOCUMENT_KEPT = Duration.ofDays(30);

	/** How long a rejected application's fields are kept (AD-24) {@code [ASSUMPTION]}. */
	static final int FIELDS_KEPT_MONTHS = 12;

	private static final Map<String, String> SORTS = Map.of("submittedAt", "m.submitted_at", "decidedAt", "d.decided_at");

	/** How a decision ended. */
	sealed interface Outcome {

		record Done(QueueRow row) implements Outcome {
		}

		record Refused(ApiException refusal, boolean endSession) implements Outcome {
		}

	}

	/** One row of ADM-13's table. */
	record QueueRow(UUID accountId, @Nullable String displayName, @Nullable String githubLogin,
			@Nullable Integer yearsOfExperience, MentorApprovalStatus status, @Nullable Instant submittedAt,
			@Nullable Instant decidedAt, long matches) {
	}

	/**
	 * Another account that shares a person-link signal with the applicant (AD-19): never the value.
	 * @param verifiedUntil a Student's verification end, if they ever verified
	 * @param erased an erased account's signals outlive it for 24 months (AD-19)
	 */
	record Match(UUID accountId, @Nullable String role, @Nullable MentorApprovalStatus mentorStatus, boolean locked,
			boolean erased, @Nullable LocalDate verifiedUntil, String kind, Instant createdAt) {
	}

	/** ADM-13's view of one application. */
	record Detail(UUID accountId, @Nullable String displayName, @Nullable String githubLogin,
			@Nullable String experience, @Nullable Integer yearsOfExperience, List<Skill> skills,
			@Nullable String linkedinUrl, @Nullable String otherLink, MentorApplications.@Nullable Document document,
			@Nullable String privacyNoticeVersion, @Nullable Instant submittedAt, MentorApprovalStatus status,
			@Nullable Instant decidedAt, @Nullable String rejectionReason, List<Match> matches) {
	}

	/** The account's latest decision, as MEN-5 shows a rejection. */
	record Decision(String decision, @Nullable String reason, @Nullable Instant reapplyAfter, Instant decidedAt) {
	}

	private final JdbcClient jdbc;

	private final MentorApplications applications;

	private final Catalog catalog;

	private final StepUps stepUps;

	private final Deadlines deadlines;

	private final Notifications notifications;

	private final ApplicationEventPublisher events;

	private final AuditLog auditLog;

	private final IdGenerator ids;

	private final Clock clock;

	MentorDecisions(JdbcClient jdbc, MentorApplications applications, Catalog catalog, StepUps stepUps,
			Deadlines deadlines, Notifications notifications, ApplicationEventPublisher events, AuditLog auditLog,
			IdGenerator ids, Clock clock) {
		this.jdbc = jdbc;
		this.applications = applications;
		this.catalog = catalog;
		this.stepUps = stepUps;
		this.deadlines = deadlines;
		this.notifications = notifications;
		this.events = events;
		this.auditLog = auditLog;
		this.ids = ids;
		this.clock = clock;
	}

	/**
	 * The submitted applications whose Mentor has {@code status}: pending ones oldest first, decided
	 * ones newest decision first.
	 * @throws ApiException {@code VALIDATION} for a sort other than {@code submittedAt} or {@code decidedAt}
	 */
	@Transactional(readOnly = true)
	Page<QueueRow> queue(MentorApprovalStatus status, Pageable pageable) {
		List<String> order = new ArrayList<>();
		if (pageable.getSort().isUnsorted()) {
			order.add(status == MentorApprovalStatus.PENDING ? "m.submitted_at ASC" : "d.decided_at DESC NULLS LAST");
		}
		for (Sort.Order part : pageable.getSort()) {
			String column = SORTS.get(part.getProperty());
			if (column == null) {
				throw new ApiException(ErrorCode.VALIDATION);
			}
			order.add(column + (part.isAscending() ? " ASC" : " DESC") + " NULLS LAST");
		}
		order.add("m.account_id ASC");
		List<QueueRow> rows = this.jdbc.sql("""
				SELECT m.account_id, p.display_name, a.github_login, p.years_of_experience, a.mentor_approval_status,
				       m.submitted_at, d.decided_at,
				       (SELECT count(DISTINCT theirs.account_id) FROM identity.person_signal mine
				        JOIN identity.person_signal theirs ON theirs.kind = mine.kind AND theirs.key_version = mine.key_version
				             AND theirs.hmac = mine.hmac AND theirs.account_id <> mine.account_id
				        WHERE mine.account_id = m.account_id) AS matches
				FROM identity.mentor_application m
				JOIN identity.account a ON a.id = m.account_id
				LEFT JOIN identity.profile p ON p.account_id = m.account_id
				LEFT JOIN identity.mentor_decision d ON d.id = m.decision_id
				WHERE m.submitted_at IS NOT NULL AND a.mentor_approval_status = :status AND a.erased_at IS NULL
				ORDER BY %s
				LIMIT :size OFFSET :offset
				""".formatted(String.join(", ", order)))
			.param("status", status.name())
			.param("size", pageable.getPageSize())
			.param("offset", pageable.getOffset())
			.query((rs, n) -> new QueueRow(rs.getObject("account_id", UUID.class), rs.getString("display_name"),
					rs.getString("github_login"), (Integer) rs.getObject("years_of_experience", Integer.class),
					MentorApprovalStatus.valueOf(rs.getString("mentor_approval_status")), instant(rs, "submitted_at"),
					instant(rs, "decided_at"), rs.getLong("matches")))
			.list();
		long total = this.jdbc.sql("""
				SELECT count(*) FROM identity.mentor_application m JOIN identity.account a ON a.id = m.account_id
				WHERE m.submitted_at IS NOT NULL AND a.mentor_approval_status = :status AND a.erased_at IS NULL
				""").param("status", status.name()).query(Long.class).single();
		return new PageImpl<>(rows, pageable, total);
	}

	/** One submitted application with its matches, if there is one. */
	@Transactional(readOnly = true)
	Optional<Detail> detail(UUID accountId) {
		record Row(@Nullable String githubLogin, MentorApprovalStatus status, @Nullable String notice,
				@Nullable Instant decidedAt, @Nullable String reason) {
		}
		Optional<MentorApplications.Application> application = this.applications.find(accountId)
			.filter(found -> found.submittedAt() != null);
		if (application.isEmpty()) {
			return Optional.empty();
		}
		Row row = this.jdbc.sql("""
				SELECT a.github_login, a.mentor_approval_status, m.privacy_notice_version, d.decided_at, d.reason
				FROM identity.account a JOIN identity.mentor_application m ON m.account_id = a.id
				LEFT JOIN identity.mentor_decision d ON d.id = m.decision_id
				WHERE a.id = :id AND a.erased_at IS NULL
				""").param("id", accountId).query((rs, n) -> new Row(rs.getString("github_login"),
				MentorApprovalStatus.valueOf(rs.getString("mentor_approval_status")), rs.getString("privacy_notice_version"),
				instant(rs, "decided_at"), rs.getString("reason"))).optional().orElse(null);
		if (row == null) {
			return Optional.empty();
		}
		MentorApplications.Application found = application.get();
		return Optional.of(new Detail(accountId, found.displayName(), row.githubLogin(), found.experience(),
				found.yearsOfExperience(), this.catalog.byIds(found.skillIds()), found.linkedinUrl(), found.otherLink(),
				found.document(), row.notice(), found.submittedAt(), row.status(), row.decidedAt(), row.reason(),
				matches(accountId)));
	}

	/** The account's latest decision, if it was ever decided. */
	Optional<Decision> latest(UUID accountId) {
		return this.jdbc.sql("""
				SELECT decision, reason, reapply_after, decided_at FROM identity.mentor_decision
				WHERE account_id = :id ORDER BY decided_at DESC LIMIT 1
				""").param("id", accountId).query((rs, n) -> new Decision(rs.getString("decision"), rs.getString("reason"),
				instant(rs, "reapply_after"), rs.getTimestamp("decided_at").toInstant())).optional();
	}

	@Transactional
	Outcome approve(UUID administrator, UUID accountId, String stepUpCode) {
		return decide(administrator, accountId, stepUpCode, null);
	}

	@Transactional
	Outcome reject(UUID administrator, UUID accountId, String reason, String stepUpCode) {
		return decide(administrator, accountId, stepUpCode, reason.strip());
	}

	private Outcome decide(UUID administrator, UUID accountId, String stepUpCode, @Nullable String reason) {
		StepUps.Outcome stepUp = this.stepUps.check(administrator, stepUpCode);
		if (stepUp instanceof StepUps.Outcome.Refused refused) {
			return new Outcome.Refused(refused.refusal(), refused.endSession());
		}
		Optional<MentorApprovalStatus> status = this.jdbc.sql("""
				SELECT mentor_approval_status FROM identity.account WHERE id = :id AND role = 'MENTOR' AND erased_at IS NULL
				FOR UPDATE
				""").param("id", accountId).query(String.class).optional().map(MentorApprovalStatus::valueOf);
		if (status.isEmpty()) {
			return new Outcome.Refused(new ApiException(ErrorCode.NOT_FOUND), false);
		}
		Optional<MentorApplications.Application> application = this.applications.find(accountId);
		if (application.isEmpty() || application.get().submittedAt() == null) {
			return new Outcome.Refused(new ApiException(ErrorCode.NOT_FOUND), false);
		}
		if (status.get() != MentorApprovalStatus.PENDING) {
			return new Outcome.Refused(new ApiException(ErrorCode.CONFLICT), false);
		}
		boolean approve = reason == null;
		Instant now = this.clock.instant().truncatedTo(ChronoUnit.MICROS);
		UUID decision = this.ids.newId();
		Instant reapplyAfter = approve ? null : now.plus(REAPPLY_WAIT);
		this.jdbc.sql("""
				INSERT INTO identity.mentor_decision (id, account_id, decision, reason, reapply_after, decided_by, decided_at)
				VALUES (:id, :account, :decision, :reason, :reapply, :admin, :now)
				""")
			.param("id", decision)
			.param("account", accountId)
			.param("decision", approve ? "APPROVED" : "REJECTED")
			.param("reason", reason)
			.param("reapply", reapplyAfter == null ? null : Timestamp.from(reapplyAfter))
			.param("admin", administrator)
			.param("now", Timestamp.from(now))
			.update();
		this.jdbc.sql("UPDATE identity.mentor_application SET decision_id = :decision WHERE account_id = :account")
			.param("decision", decision)
			.param("account", accountId)
			.update();
		this.jdbc.sql("UPDATE identity.account SET mentor_approval_status = :status WHERE id = :id")
			.param("status", approve ? "APPROVED" : "REJECTED")
			.param("id", accountId)
			.update();
		String generation = now.toString();
		if (application.get().document() != null) {
			this.deadlines.schedule(new DeadlineRequest(MentorDocumentDeletion.KEY, accountId, generation, null,
					now.plus(DOCUMENT_KEPT)));
		}
		List<Recipient> mentor = List.of(new Recipient(accountId, Audience.SUBJECT));
		if (approve) {
			this.events.publishEvent(new MentorApproved(this.ids.newId(), accountId));
			this.notifications.send(new NotificationRequest(APPROVED_NOTIFICATION, decision, mentor, Map.of(),
					SignIns.MENTOR_PROFILE_PATH, null, accountId));
		}
		else {
			this.deadlines.schedule(new DeadlineRequest(MentorReapplyWait.KEY, accountId, generation, null, reapplyAfter));
			this.deadlines.schedule(new DeadlineRequest(MentorApplicationPurge.KEY, accountId, generation, null,
					now.atOffset(ZoneOffset.UTC).plusMonths(FIELDS_KEPT_MONTHS).toInstant()));
			this.notifications.send(new NotificationRequest(REJECTED_NOTIFICATION, decision, mentor,
					Map.of("reason", reason), SignIns.MENTOR_PROFILE_PATH, null, accountId));
		}
		this.auditLog.record(AuditEntry.of(AuditActor.user(administrator),
				approve ? "identity.mentor_approved" : "identity.mentor_rejected",
				AuditObject.of("identity.account", accountId), AuditObject.of("identity.mentor_decision", decision)));
		return new Outcome.Done(new QueueRow(accountId, application.get().displayName(), null,
				application.get().yearsOfExperience(), approve ? MentorApprovalStatus.APPROVED : MentorApprovalStatus.REJECTED,
				application.get().submittedAt(), now, 0));
	}

	/** Every other account sharing a signal with {@code accountId}, by kind, never the value (AD-19). */
	private List<Match> matches(UUID accountId) {
		return this.jdbc.sql("""
				SELECT DISTINCT a.id, a.role, a.mentor_approval_status, a.locked_at IS NOT NULL AS locked,
				       a.erased_at IS NOT NULL AS erased, a.created_at, theirs.kind, v.valid_until
				FROM identity.person_signal mine
				JOIN identity.person_signal theirs ON theirs.kind = mine.kind AND theirs.key_version = mine.key_version
				     AND theirs.hmac = mine.hmac AND theirs.account_id <> mine.account_id
				JOIN identity.account a ON a.id = theirs.account_id
				LEFT JOIN identity.verification v ON v.account_id = a.id AND v.kind = 'STUDENT' AND v.ended_at IS NULL
				WHERE mine.account_id = :id
				ORDER BY a.created_at, a.id, theirs.kind
				""").param("id", accountId).query((rs, n) -> {
			String mentor = rs.getString("mentor_approval_status");
			return new Match(rs.getObject("id", UUID.class), rs.getString("role"),
					mentor == null ? null : MentorApprovalStatus.valueOf(mentor), rs.getBoolean("locked"),
					rs.getBoolean("erased"), rs.getObject("valid_until", LocalDate.class), rs.getString("kind"),
					rs.getTimestamp("created_at").toInstant());
		}).list();
	}

	/**
	 * The current decision of the application ({@code APPROVED} or {@code REJECTED}), if a Deadline of
	 * {@code generation} still serves it: no resubmission or later decision replaced it. Locks the
	 * application row.
	 */
	static Optional<String> currentDecision(JdbcClient jdbc, UUID accountId, String generation) {
		record Current(String decision, Instant decidedAt) {
		}
		return jdbc.sql("""
				SELECT d.decision, d.decided_at
				FROM identity.mentor_application m JOIN identity.mentor_decision d ON d.id = m.decision_id
				WHERE m.account_id = :account FOR UPDATE OF m
				""")
			.param("account", accountId)
			.query((rs, n) -> new Current(rs.getString("decision"), rs.getTimestamp("decided_at").toInstant()))
			.optional()
			.filter(current -> current.decidedAt().toString().equals(generation))
			.map(Current::decision);
	}

	private static @Nullable Instant instant(ResultSet rs, String column) throws SQLException {
		Timestamp value = rs.getTimestamp(column);
		return value == null ? null : value.toInstant();
	}

}
