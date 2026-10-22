package vn.vibefix.dispute.internal;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import vn.vibefix.audit.AuditActor;
import vn.vibefix.audit.AuditEntry;
import vn.vibefix.audit.AuditLog;
import vn.vibefix.audit.AuditObject;
import vn.vibefix.identity.Administrators;
import vn.vibefix.job.JobFreezes;
import vn.vibefix.job.JobFreezes.DisputedJob;
import vn.vibefix.job.JobRules;
import vn.vibefix.notification.Audience;
import vn.vibefix.notification.NotificationRequest;
import vn.vibefix.notification.Notifications;
import vn.vibefix.notification.Recipient;
import vn.vibefix.notification.TodoRequest;
import vn.vibefix.notification.Todos;
import vn.vibefix.shared.ApiException;
import vn.vibefix.shared.ErrorCode;
import vn.vibefix.shared.IdGenerator;
import vn.vibefix.timers.DeadlineRequest;
import vn.vibefix.timers.Deadlines;

/**
 * Opening a Dispute (FR-52, AD-28, AD-31): never refused for a busy Job; in one transaction it records
 * the Dispute and what it concerns (the Held Milestone, or the Job's next state), freezes the Job, and
 * pauses its pausable Deadlines; the other side and every active Administrator are told. The other side
 * (both sides of a System Dispute) gets the statement period of the Job's copied
 * {@code DISPUTE_RESPONSE_DAYS} (Story 15.1): the to-do {@value #SUBMIT_STATEMENT} and a non-pausable
 * Deadline that ends it.
 */
@Component
class Disputes {

	static final String OPENED = "dispute.dispute_opened";

	static final String SUBMIT_STATEMENT = "dispute.submit_statement";

	/** The FR-61 default of the statement period, for a Job published before the rule was copied. */
	static final int RESPONSE_DAYS = 5;

	static final Set<String> DISPUTABLE = Set.of("AGREEMENT", "STARTING", "ACTIVE", "CLOSING");

	enum Side {
		CUSTOMER, MENTOR, SYSTEM
	}

	/** The reason a System Dispute gives (FR-59). */
	static final String LOCKED_REASON = "Một bên tham gia bị khóa tài khoản";

	/** @param statementDueAt when the other side's statement period ends (Story 15.1) */
	record Dispute(UUID id, UUID jobId, Side openedBy, String reason, String concern, @Nullable UUID milestoneId,
			String jobState, String status, Instant openedAt, Instant statementDueAt) {
	}

	private final JdbcClient jdbc;

	private final JobFreezes freezes;

	private final Deadlines deadlines;

	private final Administrators administrators;

	private final Notifications notifications;

	private final AuditLog auditLog;

	private final IdGenerator ids;

	private final Clock clock;

	private final JobRules rules;

	private final Todos todos;

	Disputes(JdbcClient jdbc, JobFreezes freezes, Deadlines deadlines, Administrators administrators,
			Notifications notifications, AuditLog auditLog, IdGenerator ids, Clock clock, JobRules rules, Todos todos) {
		this.rules = rules;
		this.todos = todos;
		this.jdbc = jdbc;
		this.freezes = freezes;
		this.deadlines = deadlines;
		this.administrators = administrators;
		this.notifications = notifications;
		this.auditLog = auditLog;
		this.ids = ids;
		this.clock = clock;
	}

	/**
	 * @throws ApiException {@code GUARD_FAILED JOB_NOT_DISPUTABLE}; {@code CONFLICT DISPUTE_ALREADY_OPEN};
	 * {@code VALIDATION}
	 */
	@Transactional
	Dispute open(UUID jobId, UUID account, Side side, String reason) {
		DisputedJob job = this.freezes.lock(jobId).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
		if (!DISPUTABLE.contains(job.state()) || job.mentorId() == null) {
			throw new ApiException(ErrorCode.GUARD_FAILED, DisputeReason.JOB_NOT_DISPUTABLE);
		}
		if (open(jobId).isPresent()) {
			throw new ApiException(ErrorCode.CONFLICT, DisputeReason.DISPUTE_ALREADY_OPEN);
		}
		String text = reason.strip();
		if (text.isEmpty() || text.length() > 2000) {
			throw new ApiException(ErrorCode.VALIDATION);
		}
		UUID id = this.ids.newId();
		Instant now = this.clock.instant();
		int days = this.rules.value(jobId, "DISPUTE_RESPONSE_DAYS", RESPONSE_DAYS);
		this.jdbc.sql("""
				INSERT INTO dispute.dispute (id, job_id, product_id, opened_by, opener_account_id, reason, concern, milestone_id,
				    job_state, status, opened_at, response_days, statement_due_at)
				VALUES (:id, :job, :product, :side, :account, :reason, :concern, :milestone, :state, 'OPEN', :now, :days, :due)
				""")
			.param("id", id)
			.param("job", jobId)
			.param("product", job.productId())
			.param("side", side.name())
			.param("account", account)
			.param("reason", text)
			.param("concern", job.heldMilestoneId() != null ? "HELD_MILESTONE" : "NEXT_STATE")
			.param("milestone", job.heldMilestoneId())
			.param("state", job.state())
			.param("now", Timestamp.from(now))
			.param("days", days)
			.param("due", Timestamp.from(now.plus(Duration.ofDays(days))))
			.update();
		this.freezes.freeze(jobId);
		this.deadlines.freeze(jobId);
		statementPeriod(id, job, side, now.plus(Duration.ofDays(days)));
		this.auditLog.record(AuditEntry.of(AuditActor.user(account), "dispute.dispute_opened",
				AuditObject.of("dispute.dispute", id)).forJob(jobId));
		List<Recipient> recipients = new ArrayList<>();
		recipients.add(side == Side.CUSTOMER ? new Recipient(job.mentorId(), Audience.MENTOR)
				: new Recipient(job.customerId(), Audience.CUSTOMER));
		this.administrators.activeIds().forEach(admin -> recipients.add(new Recipient(admin, Audience.ADMIN)));
		this.notifications.send(new NotificationRequest(OPENED, id, recipients, Map.of("job", job.title()),
				"/cong-viec/" + jobId, jobId, null));
		return find(id).orElseThrow();
	}

	/**
	 * {@code JobNeedsDecision} (FR-59, AD-19): one open Dispute by the System on a running Job, which
	 * freezes it; nothing when one is open already or the Job moved on.
	 * @return whether it opened one
	 */
	@Transactional
	boolean openForSystem(UUID jobId) {
		DisputedJob job = this.freezes.lock(jobId).orElse(null);
		if (job == null || !DISPUTABLE.contains(job.state()) || job.mentorId() == null || open(jobId).isPresent()) {
			return false;
		}
		UUID id = this.ids.newId();
		Instant now = this.clock.instant();
		int days = this.rules.value(jobId, "DISPUTE_RESPONSE_DAYS", RESPONSE_DAYS);
		this.jdbc.sql("""
				INSERT INTO dispute.dispute (id, job_id, product_id, opened_by, opener_account_id, reason, concern, milestone_id,
				    job_state, status, opened_at, response_days, statement_due_at)
				VALUES (:id, :job, :product, 'SYSTEM', NULL, :reason, :concern, :milestone, :state, 'OPEN', :now, :days, :due)
				""")
			.param("id", id)
			.param("job", jobId)
			.param("product", job.productId())
			.param("reason", LOCKED_REASON)
			.param("concern", job.heldMilestoneId() != null ? "HELD_MILESTONE" : "NEXT_STATE")
			.param("milestone", job.heldMilestoneId())
			.param("state", job.state())
			.param("now", Timestamp.from(now))
			.param("days", days)
			.param("due", Timestamp.from(now.plus(Duration.ofDays(days))))
			.update();
		this.freezes.freeze(jobId);
		this.deadlines.freeze(jobId);
		statementPeriod(id, job, Side.SYSTEM, now.plus(Duration.ofDays(days)));
		this.auditLog.record(AuditEntry.of(AuditActor.system(), "dispute.dispute_opened", AuditObject.of("dispute.dispute", id))
			.forJob(jobId));
		List<Recipient> recipients = new ArrayList<>(List.of(new Recipient(job.customerId(), Audience.CUSTOMER),
				new Recipient(job.mentorId(), Audience.MENTOR)));
		this.administrators.activeIds().forEach(admin -> recipients.add(new Recipient(admin, Audience.ADMIN)));
		this.notifications.send(new NotificationRequest(OPENED, id, recipients, Map.of("job", job.title()),
				"/cong-viec/" + jobId, jobId, null));
		return true;
	}

	/** The other side's to-do and the Deadline that ends its period (Story 15.1). */
	private void statementPeriod(UUID disputeId, DisputedJob job, Side opener, Instant due) {
		this.deadlines.schedule(new DeadlineRequest(StatementPeriod.KEY, disputeId, disputeId.toString(), job.jobId(), due));
		String link = "/cong-viec/" + job.jobId();
		if (opener != Side.CUSTOMER) {
			this.todos.open(new TodoRequest(SUBMIT_STATEMENT, job.customerId(), Audience.CUSTOMER, disputeId, Map.of(), link, due,
					job.jobId()));
		}
		if (opener != Side.MENTOR && job.mentorId() != null) {
			this.todos.open(new TodoRequest(SUBMIT_STATEMENT, job.mentorId(), Audience.MENTOR, disputeId, Map.of(), link, due,
					job.jobId()));
		}
	}

	Optional<Dispute> open(UUID jobId) {
		return this.jdbc.sql("SELECT * FROM dispute.dispute WHERE job_id = :job AND status = 'OPEN'")
			.param("job", jobId)
			.query(Disputes::dispute)
			.optional();
	}

	Optional<Dispute> latestDecided(UUID jobId) {
		return this.jdbc.sql("""
				SELECT * FROM dispute.dispute WHERE job_id = :job AND status = 'DECIDED' ORDER BY decided_at DESC, id LIMIT 1
				""").param("job", jobId).query(Disputes::dispute).optional();
	}

	Optional<Dispute> find(UUID id) {
		return this.jdbc.sql("SELECT * FROM dispute.dispute WHERE id = :id").param("id", id).query(Disputes::dispute).optional();
	}

	private static Dispute dispute(ResultSet rs, int n) throws SQLException {
		Instant opened = rs.getTimestamp("opened_at").toInstant();
		Timestamp due = rs.getTimestamp("statement_due_at");
		return new Dispute(rs.getObject("id", UUID.class), rs.getObject("job_id", UUID.class),
				Side.valueOf(rs.getString("opened_by")), rs.getString("reason"), rs.getString("concern"),
				rs.getObject("milestone_id", UUID.class), rs.getString("job_state"), rs.getString("status"), opened,
				due != null ? due.toInstant() : opened.plus(Duration.ofDays(rs.getInt("response_days"))));
	}

}
