package vn.vibefix.dispute.internal;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
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
import vn.vibefix.notification.Audience;
import vn.vibefix.notification.NotificationRequest;
import vn.vibefix.notification.Notifications;
import vn.vibefix.notification.Recipient;
import vn.vibefix.shared.ApiException;
import vn.vibefix.shared.ErrorCode;
import vn.vibefix.shared.IdGenerator;
import vn.vibefix.timers.Deadlines;

/**
 * Opening a Dispute (FR-52, AD-28, AD-31): never refused for a busy Job; in one transaction it records
 * the Dispute and what it concerns (the Held Milestone, or the Job's next state), freezes the Job, and
 * pauses its pausable Deadlines; the other side and every active Administrator are told.
 */
@Component
class Disputes {

	static final String OPENED = "dispute.dispute_opened";

	static final Set<String> DISPUTABLE = Set.of("AGREEMENT", "STARTING", "ACTIVE", "CLOSING");

	enum Side {
		CUSTOMER, MENTOR, SYSTEM
	}

	/** The reason a System Dispute gives (FR-59). */
	static final String LOCKED_REASON = "Một bên tham gia bị khóa tài khoản";

	record Dispute(UUID id, UUID jobId, Side openedBy, String reason, String concern, @Nullable UUID milestoneId,
			String jobState, String status, Instant openedAt) {
	}

	private final JdbcClient jdbc;

	private final JobFreezes freezes;

	private final Deadlines deadlines;

	private final Administrators administrators;

	private final Notifications notifications;

	private final AuditLog auditLog;

	private final IdGenerator ids;

	private final Clock clock;

	Disputes(JdbcClient jdbc, JobFreezes freezes, Deadlines deadlines, Administrators administrators,
			Notifications notifications, AuditLog auditLog, IdGenerator ids, Clock clock) {
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
		this.jdbc.sql("""
				INSERT INTO dispute.dispute (id, job_id, product_id, opened_by, opener_account_id, reason, concern, milestone_id,
				    job_state, status, opened_at)
				VALUES (:id, :job, :product, :side, :account, :reason, :concern, :milestone, :state, 'OPEN', :now)
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
			.update();
		this.freezes.freeze(jobId);
		this.deadlines.freeze(jobId);
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
		this.jdbc.sql("""
				INSERT INTO dispute.dispute (id, job_id, product_id, opened_by, opener_account_id, reason, concern, milestone_id,
				    job_state, status, opened_at)
				VALUES (:id, :job, :product, 'SYSTEM', NULL, :reason, :concern, :milestone, :state, 'OPEN', :now)
				""")
			.param("id", id)
			.param("job", jobId)
			.param("product", job.productId())
			.param("reason", LOCKED_REASON)
			.param("concern", job.heldMilestoneId() != null ? "HELD_MILESTONE" : "NEXT_STATE")
			.param("milestone", job.heldMilestoneId())
			.param("state", job.state())
			.param("now", Timestamp.from(this.clock.instant()))
			.update();
		this.freezes.freeze(jobId);
		this.deadlines.freeze(jobId);
		this.auditLog.record(AuditEntry.of(AuditActor.system(), "dispute.dispute_opened", AuditObject.of("dispute.dispute", id))
			.forJob(jobId));
		List<Recipient> recipients = new ArrayList<>(List.of(new Recipient(job.customerId(), Audience.CUSTOMER),
				new Recipient(job.mentorId(), Audience.MENTOR)));
		this.administrators.activeIds().forEach(admin -> recipients.add(new Recipient(admin, Audience.ADMIN)));
		this.notifications.send(new NotificationRequest(OPENED, id, recipients, Map.of("job", job.title()),
				"/cong-viec/" + jobId, jobId, null));
		return true;
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
		return new Dispute(rs.getObject("id", UUID.class), rs.getObject("job_id", UUID.class),
				Side.valueOf(rs.getString("opened_by")), rs.getString("reason"), rs.getString("concern"),
				rs.getObject("milestone_id", UUID.class), rs.getString("job_state"), rs.getString("status"),
				rs.getTimestamp("opened_at").toInstant());
	}

}
