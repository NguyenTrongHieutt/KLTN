package vn.vibefix.dispute.internal;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.jspecify.annotations.Nullable;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import vn.vibefix.audit.AuditActor;
import vn.vibefix.audit.AuditEntry;
import vn.vibefix.audit.AuditLog;
import vn.vibefix.audit.AuditObject;
import vn.vibefix.dispute.internal.Disputes.Dispute;
import vn.vibefix.dispute.internal.Disputes.Side;
import vn.vibefix.identity.Administrators;
import vn.vibefix.job.JobPosts;
import vn.vibefix.notification.Audience;
import vn.vibefix.notification.NotificationRequest;
import vn.vibefix.notification.Notifications;
import vn.vibefix.notification.Recipient;
import vn.vibefix.notification.Todos;
import vn.vibefix.shared.ApiException;
import vn.vibefix.shared.ErrorCode;

/**
 * The other side's statement (FR-52, AD-31): during the Dispute's copied period, and until it is decided,
 * the side that did not open it (either side of a System Dispute) sends one statement; its to-do closes and
 * every active Administrator is told. A decision waits until a statement arrived or the period passed.
 */
@Component
class DisputeStatements {

	static final String SUBMITTED = "dispute.statement_submitted";

	/** One side's statement. */
	record Statement(Side side, @Nullable UUID accountId, String text, Instant submittedAt) {
	}

	private final JdbcClient jdbc;

	private final Disputes disputes;

	private final JobPosts posts;

	private final Administrators administrators;

	private final Notifications notifications;

	private final Todos todos;

	private final AuditLog auditLog;

	private final Clock clock;

	DisputeStatements(JdbcClient jdbc, Disputes disputes, JobPosts posts, Administrators administrators,
			Notifications notifications, Todos todos, AuditLog auditLog, Clock clock) {
		this.jdbc = jdbc;
		this.disputes = disputes;
		this.posts = posts;
		this.administrators = administrators;
		this.notifications = notifications;
		this.todos = todos;
		this.auditLog = auditLog;
		this.clock = clock;
	}

	/**
	 * "Gửi trình bày".
	 * @throws ApiException {@code NOT_FOUND} without an open Dispute; {@code FORBIDDEN NOT_THE_OTHER_SIDE};
	 * {@code CONFLICT STATEMENT_ALREADY_SENT}; {@code VALIDATION}
	 */
	@Transactional
	void submit(UUID jobId, UUID account, Side side, String text) {
		Dispute dispute = this.disputes.open(jobId).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
		if (!mayState(dispute, side)) {
			throw new ApiException(ErrorCode.FORBIDDEN, DisputeReason.NOT_THE_OTHER_SIDE);
		}
		String statement = text.strip();
		if (statement.isEmpty() || statement.length() > 4000) {
			throw new ApiException(ErrorCode.VALIDATION);
		}
		try {
			this.jdbc.sql("""
					INSERT INTO dispute.statement (dispute_id, side, account_id, text, submitted_at)
					VALUES (:dispute, :side, :account, :text, :now)
					""")
				.param("dispute", dispute.id())
				.param("side", side.name())
				.param("account", account)
				.param("text", statement)
				.param("now", Timestamp.from(this.clock.instant()))
				.update();
		}
		catch (DuplicateKeyException again) {
			throw new ApiException(ErrorCode.CONFLICT, DisputeReason.STATEMENT_ALREADY_SENT);
		}
		this.todos.close(Disputes.SUBMIT_STATEMENT, account, dispute.id());
		this.auditLog.record(AuditEntry.of(AuditActor.user(account), "dispute.statement_submitted",
				AuditObject.of("dispute.dispute", dispute.id())).forJob(jobId));
		String title = this.posts.post(jobId).map(JobPosts.JobPostState::title).orElse("");
		List<Recipient> admins = this.administrators.activeIds().stream().map(admin -> new Recipient(admin, Audience.ADMIN)).toList();
		if (!admins.isEmpty()) {
			this.notifications.send(new NotificationRequest(SUBMITTED, dispute.id(), admins, Map.of("job", title),
					"/quan-tri/tranh-chap/" + dispute.id(), jobId, null));
		}
	}

	/** The side that did not open the Dispute; either side of a System Dispute. */
	static boolean mayState(Dispute dispute, @Nullable Side side) {
		return side != null && side != Side.SYSTEM && dispute.openedBy() != side;
	}

	List<Statement> of(UUID disputeId) {
		return this.jdbc.sql("SELECT side, account_id, text, submitted_at FROM dispute.statement WHERE dispute_id = :id ORDER BY submitted_at")
			.param("id", disputeId)
			.query((rs, n) -> new Statement(Side.valueOf(rs.getString("side")), rs.getObject("account_id", UUID.class),
					rs.getString("text"), rs.getTimestamp("submitted_at").toInstant()))
			.list();
	}

	/** Whether the decision may be made now: a statement arrived, or the period passed by the Clock. */
	boolean decidable(Dispute dispute) {
		return !of(dispute.id()).isEmpty() || !this.clock.instant().isBefore(dispute.statementDueAt());
	}

}
