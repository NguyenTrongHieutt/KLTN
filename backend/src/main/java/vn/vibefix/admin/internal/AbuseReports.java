package vn.vibefix.admin.internal;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import vn.vibefix.audit.AuditActor;
import vn.vibefix.audit.AuditEntry;
import vn.vibefix.audit.AuditLog;
import vn.vibefix.audit.AuditObject;
import vn.vibefix.identity.MentorDirectory;
import vn.vibefix.identity.StudentDirectory;
import vn.vibefix.notification.Audience;
import vn.vibefix.notification.NotificationRequest;
import vn.vibefix.notification.Notifications;
import vn.vibefix.notification.Recipient;
import vn.vibefix.shared.AdminReadGrants;
import vn.vibefix.shared.AdminReadItem;
import vn.vibefix.shared.AdminReadScope;
import vn.vibefix.shared.AdvisoryLocks;
import vn.vibefix.shared.ApiException;
import vn.vibefix.shared.Caller;
import vn.vibefix.shared.ErrorCode;
import vn.vibefix.shared.IdGenerator;
import vn.vibefix.shared.RateLimitedException;
import vn.vibefix.shared.ReportableContent;
import vn.vibefix.shared.ReportableContent.Reported;

/**
 * Abuse Reports (FR-69, AD-20, AD-34): any signed-in user but an Administrator files one on content or a user
 * the owning module says they can see, at most 10 a day. An Administrator takes a report; for a chat message
 * or a Work Item note that pushes a read grant on that one item down to {@code chat} and {@code work}, a
 * takeover ends the old grant at once, and the decision ends it 30 days later. The assignee hides the content
 * through FR-60, records an action taken elsewhere, or dismisses the report, and the reporter is told.
 */
@Component
class AbuseReports {

	/** At most this many reports per account per day (AD-34). */
	static final int DAILY_LIMIT = 10;

	/** How long the assignee still reads a reported message or note after the decision (AD-20). */
	static final Duration GRANT_AFTER_DECISION = Duration.ofDays(30);

	static final String RESOLVED = "admin.abuse_report_resolved";

	static final Set<String> KINDS = Set.of("PRODUCT", "JOB_POST", "PAGE", "RATING", "JOB_CHAT_MESSAGE", "CREW_CHAT_MESSAGE",
			"WORK_ITEM_NOTE", "USER");

	private static final ZoneId VIETNAM = ZoneId.of("Asia/Ho_Chi_Minh");

	private static final Map<String, AdminReadScope> GRANTED = Map.of("JOB_CHAT_MESSAGE", AdminReadScope.JOB_CHAT,
			"CREW_CHAT_MESSAGE", AdminReadScope.CREW_CHAT, "WORK_ITEM_NOTE", AdminReadScope.WORK_ITEM_NOTES);

	/** How an Administrator decided: hid the content (FR-60), acted elsewhere, or dismissed the report. */
	enum Action {

		HIDE("HIDDEN"), ACTION_TAKEN("ACTION_TAKEN"), DISMISS("DISMISSED");

		private final String status;

		Action(String status) {
			this.status = status;
		}

	}

	/**
	 * One report.
	 * @param status {@code OPEN}, {@code HIDDEN}, {@code ACTION_TAKEN}, or {@code DISMISSED}
	 */
	record Report(UUID id, @Nullable UUID reporterId, String kind, UUID targetId, @Nullable UUID jobId, @Nullable UUID productId,
			@Nullable UUID authorId, String label, String reason, String status, Instant createdAt, @Nullable UUID assigneeId,
			@Nullable UUID grantId, @Nullable String outcome, @Nullable Instant decidedAt) {

		/** A report whose decision gives its assignee a grant on the one item it names. */
		boolean granted() {
			return GRANTED.containsKey(this.kind) && this.jobId != null;
		}

	}

	private static final String COLUMNS = """
			id, reporter_account_id, kind, target_id, job_id, product_id, author_account_id, label, reason, status, created_at,
			assigned_admin_id, grant_id, outcome, decided_at
			""";

	private final JdbcClient jdbc;

	private final List<ReportableContent> reportables;

	private final List<AdminReadGrants> grants;

	private final ContentHiding hiding;

	private final Notifications notifications;

	private final AuditLog auditLog;

	private final IdGenerator ids;

	private final Clock clock;

	private final TransactionTemplate transactions;

	private final MentorDirectory mentors;

	private final StudentDirectory students;

	AbuseReports(JdbcClient jdbc, List<ReportableContent> reportables, List<AdminReadGrants> grants, ContentHiding hiding,
			Notifications notifications, AuditLog auditLog, IdGenerator ids, Clock clock,
			PlatformTransactionManager transactionManager, MentorDirectory mentors, StudentDirectory students) {
		this.mentors = mentors;
		this.students = students;
		this.jdbc = jdbc;
		this.reportables = reportables;
		this.grants = grants;
		this.hiding = hiding;
		this.notifications = notifications;
		this.auditLog = auditLog;
		this.ids = ids;
		this.clock = clock;
		this.transactions = new TransactionTemplate(transactionManager);
	}

	/**
	 * "Báo cáo vi phạm": files a report on what the caller can see now. The owning module's check runs before
	 * the write's transaction, so a refused read cannot mark it rollback-only.
	 * @throws ApiException {@code NOT_FOUND} for an Administrator or an item the caller cannot see;
	 * {@code VALIDATION}; {@code CONFLICT ALREADY_REPORTED}; {@code RATE_LIMITED ABUSE_REPORT_LIMIT}
	 */
	UUID file(Caller caller, String kind, @Nullable UUID item, @Nullable String login, String reason) {
		if (caller.isAdministrator()) {
			throw new ApiException(ErrorCode.NOT_FOUND);
		}
		String text = reason.strip();
		if (!KINDS.contains(kind) || text.isEmpty() || text.length() > 500 || (item == null) == (login == null)
				|| (login != null && !"USER".equals(kind))) {
			throw new ApiException(ErrorCode.VALIDATION);
		}
		UUID targetId = item != null ? item : accountOf(login);
		UUID reporter = caller.requireAccountId();
		Reported reported = this.reportables.stream()
			.filter(module -> module.kinds().contains(kind))
			.findFirst()
			.flatMap(module -> module.reportable(kind, targetId, caller))
			.orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
		UUID id = this.ids.newId();
		this.transactions.executeWithoutResult(status -> {
			AdvisoryLocks.lockAccount(this.jdbc, reporter);
			Instant now = this.clock.instant();
			Instant today = LocalDate.ofInstant(now, VIETNAM).atStartOfDay(VIETNAM).toInstant();
			long filed = this.jdbc.sql("SELECT count(*) FROM admin.abuse_report WHERE reporter_account_id = :reporter AND created_at >= :since")
				.param("reporter", reporter)
				.param("since", Timestamp.from(today))
				.query(Long.class)
				.single();
			if (filed >= DAILY_LIMIT) {
				Instant tomorrow = LocalDate.ofInstant(now, VIETNAM).plusDays(1).atStartOfDay(VIETNAM).toInstant();
				throw new RateLimitedException(Duration.between(now, tomorrow), AdminReason.ABUSE_REPORT_LIMIT);
			}
			boolean open = this.jdbc.sql("""
					SELECT EXISTS (SELECT 1 FROM admin.abuse_report
					               WHERE reporter_account_id = :reporter AND kind = :kind AND target_id = :target AND status = 'OPEN')
					""").param("reporter", reporter).param("kind", kind).param("target", targetId).query(Boolean.class).single();
			if (open) {
				throw new ApiException(ErrorCode.CONFLICT, AdminReason.ALREADY_REPORTED);
			}
			this.jdbc.sql("""
					INSERT INTO admin.abuse_report (id, reporter_account_id, kind, target_id, job_id, product_id, author_account_id,
					    label, reason, status, created_at)
					VALUES (:id, :reporter, :kind, :target, :job, :product, :author, :label, :reason, 'OPEN', :now)
					""")
				.param("id", id)
				.param("reporter", reporter)
				.param("kind", kind)
				.param("target", targetId)
				.param("job", reported.jobId())
				.param("product", reported.productId())
				.param("author", reported.authorId())
				.param("label", label(reported.label()))
				.param("reason", text)
				.param("now", Timestamp.from(now))
				.update();
			AuditEntry entry = AuditEntry.of(AuditActor.user(reporter), "admin.abuse_report_filed",
					AuditObject.of("admin.abuse_report", id));
			this.auditLog.record(reported.jobId() == null ? entry : entry.forJob(reported.jobId()));
		});
		return id;
	}

	/** The account a public profile shows under {@code login}: a Mentor's (PUB-5) or a Student's (PUB-4). */
	private UUID accountOf(String login) {
		return this.mentors.byLogin(login)
			.map(profile -> profile.card().accountId())
			.or(() -> this.students.profileByLogin(login).map(profile -> profile.card().accountId()))
			.orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
	}

	/** The open reports, oldest first, then the decided ones, newest first; at most 200. */
	@Transactional(readOnly = true)
	List<Report> queue() {
		return this.jdbc.sql("SELECT " + COLUMNS + """
				FROM admin.abuse_report
				ORDER BY status = 'OPEN' DESC, CASE WHEN status = 'OPEN' THEN created_at END ASC, created_at DESC, id
				LIMIT 200
				""").query(AbuseReports::report).list();
	}

	@Transactional(readOnly = true)
	Optional<Report> find(UUID reportId) {
		return this.jdbc.sql("SELECT " + COLUMNS + " FROM admin.abuse_report WHERE id = :id")
			.param("id", reportId)
			.query(AbuseReports::report)
			.optional();
	}

	/**
	 * "Nhận xử lý": the Administrator takes the report, over another if need be. For a chat message or a Work
	 * Item note this opens a grant on that one item; the assignee taking it again changes nothing.
	 * @throws ApiException {@code NOT_FOUND}; {@code CONFLICT ALREADY_DECIDED}
	 */
	@Transactional
	Report take(UUID reportId, UUID administrator) {
		Report report = locked(reportId);
		if (!"OPEN".equals(report.status())) {
			throw new ApiException(ErrorCode.CONFLICT, AdminReason.ALREADY_DECIDED);
		}
		if (administrator.equals(report.assigneeId()) && (report.grantId() != null || !report.granted())) {
			return report;
		}
		Instant now = this.clock.instant();
		UUID previous = report.grantId();
		if (previous != null) {
			this.grants.forEach(module -> module.endAdminRead(previous, now));
		}
		UUID grant = null;
		if (report.granted()) {
			UUID opened = this.ids.newId();
			UUID job = report.jobId();
			this.grants.forEach(module -> module.grantAdminRead(opened, administrator, job,
					AdminReadItem.forAbuseReport(GRANTED.get(report.kind()), report.targetId()), reportId));
			grant = opened;
		}
		this.jdbc.sql("UPDATE admin.abuse_report SET assigned_admin_id = :admin, assigned_at = :now, grant_id = :grant WHERE id = :id")
			.param("admin", administrator)
			.param("now", Timestamp.from(now))
			.param("grant", grant)
			.param("id", reportId)
			.update();
		audit(administrator, "admin.abuse_report_taken", report);
		return find(reportId).orElseThrow();
	}

	/**
	 * The assignee's decision, with the outcome the reporter reads.
	 * @throws ApiException {@code NOT_FOUND}; {@code CONFLICT ALREADY_DECIDED}; {@code CONFLICT NOT_ASSIGNED} for an
	 * Administrator who did not take it (SG-31: every Administrator reads the report, so no {@code FORBIDDEN});
	 * {@code VALIDATION}, with {@code NOT_HIDEABLE} for a kind FR-60 does not hide; {@code CONFLICT ALREADY_HIDDEN}
	 */
	@Transactional
	Report decide(UUID reportId, UUID administrator, Action action, @Nullable String hideReason, String outcome) {
		Report report = locked(reportId);
		if (!"OPEN".equals(report.status())) {
			throw new ApiException(ErrorCode.CONFLICT, AdminReason.ALREADY_DECIDED);
		}
		if (!administrator.equals(report.assigneeId())) {
			throw new ApiException(ErrorCode.CONFLICT, AdminReason.NOT_ASSIGNED);
		}
		String told = outcome.strip();
		if (told.isEmpty() || told.length() > 500) {
			throw new ApiException(ErrorCode.VALIDATION);
		}
		if (action == Action.HIDE) {
			if (!ContentHiding.KINDS.contains(report.kind())) {
				throw new ApiException(ErrorCode.VALIDATION, AdminReason.NOT_HIDEABLE);
			}
			String why = hideReason == null ? "" : hideReason.strip();
			if (why.isEmpty() || why.length() > 500) {
				throw new ApiException(ErrorCode.VALIDATION);
			}
			this.hiding.hide(report.kind(), report.targetId(), why, administrator);
		}
		Instant now = this.clock.instant();
		this.jdbc.sql("""
				UPDATE admin.abuse_report SET status = :status, decided_by_admin_id = :admin, outcome = :outcome, decided_at = :now
				WHERE id = :id
				""")
			.param("status", action.status)
			.param("admin", administrator)
			.param("outcome", told)
			.param("now", Timestamp.from(now))
			.param("id", reportId)
			.update();
		UUID grant = report.grantId();
		if (grant != null) {
			Instant ends = now.plus(GRANT_AFTER_DECISION);
			this.grants.forEach(module -> module.endAdminRead(grant, ends));
		}
		audit(administrator, "admin.abuse_report_decided", report);
		if (report.reporterId() != null) {
			this.notifications.send(new NotificationRequest(RESOLVED, reportId,
					List.of(new Recipient(report.reporterId(), Audience.SUBJECT)), Map.of("outcome", told), "/thong-bao", null,
					report.reporterId()));
		}
		return find(reportId).orElseThrow();
	}

	/**
	 * Whether {@code administrator} reads the reported message or note now: the assignee, under the grant
	 * their take opened, until 30 days after the decision.
	 */
	boolean itemReadable(Report report, UUID administrator) {
		if (!report.granted() || report.grantId() == null || !administrator.equals(report.assigneeId())) {
			return false;
		}
		return report.decidedAt() == null || this.clock.instant().isBefore(report.decidedAt().plus(GRANT_AFTER_DECISION));
	}

	private Report locked(UUID reportId) {
		return this.jdbc.sql("SELECT " + COLUMNS + " FROM admin.abuse_report WHERE id = :id FOR UPDATE")
			.param("id", reportId)
			.query(AbuseReports::report)
			.optional()
			.orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
	}

	private void audit(UUID administrator, String action, Report report) {
		AuditEntry entry = AuditEntry.of(AuditActor.user(administrator), action, AuditObject.of("admin.abuse_report", report.id()));
		this.auditLog.record(report.jobId() == null ? entry : entry.forJob(report.jobId()));
	}

	private static String label(String label) {
		String text = label.strip();
		if (text.isEmpty()) {
			return "—";
		}
		return text.length() > 300 ? text.substring(0, 300) : text;
	}

	private static Report report(ResultSet rs, int n) throws SQLException {
		Timestamp decided = rs.getTimestamp("decided_at");
		return new Report(rs.getObject("id", UUID.class), rs.getObject("reporter_account_id", UUID.class), rs.getString("kind"),
				rs.getObject("target_id", UUID.class), rs.getObject("job_id", UUID.class), rs.getObject("product_id", UUID.class),
				rs.getObject("author_account_id", UUID.class), rs.getString("label"), rs.getString("reason"),
				rs.getString("status"), rs.getTimestamp("created_at").toInstant(), rs.getObject("assigned_admin_id", UUID.class),
				rs.getObject("grant_id", UUID.class), rs.getString("outcome"), decided == null ? null : decided.toInstant());
	}

}
