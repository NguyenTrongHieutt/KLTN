package vn.vibefix.identity.internal;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import vn.vibefix.audit.AuditActor;
import vn.vibefix.audit.AuditEntry;
import vn.vibefix.audit.AuditLog;
import vn.vibefix.audit.AuditObject;
import vn.vibefix.notification.Audience;
import vn.vibefix.notification.NotificationRequest;
import vn.vibefix.notification.Notifications;
import vn.vibefix.notification.Recipient;
import vn.vibefix.notification.TodoRequest;
import vn.vibefix.notification.Todos;
import vn.vibefix.timers.DeadlineHandler;
import vn.vibefix.timers.DeadlineKind;
import vn.vibefix.timers.DeadlineOutcome;
import vn.vibefix.timers.DeadlineRequest;
import vn.vibefix.timers.Deadlines;
import vn.vibefix.timers.DueDeadline;

/**
 * The Deadlines of a Student verification (FR-5, AD-7, UX-DR16): a reminder 14 days before it
 * expires, and the expiry at the start of the day after its last valid day, in Vietnam. Both serve
 * the verification as of its {@code verified_at}; a renewal or an erasure leaves them {@code OBSOLETE}.
 */
@Component
class VerificationDeadlines {

	static final DeadlineKind REMINDER = new DeadlineKind("identity.verification_reminder", false, false);

	static final DeadlineKind EXPIRY = new DeadlineKind("identity.verification_expiry", false, false);

	/** The reminder's lead, which is also when renewal opens. */
	static final Duration LEAD = Duration.ofDays(14);

	static final String VERIFICATION_EXPIRING = "identity.verification_expiring";

	static final String VERIFICATION_EXPIRED = "identity.verification_expired";

	private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd/MM/yyyy");

	private final Deadlines deadlines;

	VerificationDeadlines(Deadlines deadlines) {
		this.deadlines = deadlines;
	}

	/** The instant a verification valid through {@code validUntil} expires: that day's end in Vietnam. */
	static Instant expiresAt(LocalDate validUntil) {
		return validUntil.plusDays(1).atStartOfDay(StudentVerifications.VIETNAM).toInstant();
	}

	/** Schedules the expiry, and the reminder unless its time has passed already. */
	void schedule(UUID accountId, Instant verifiedAt, LocalDate validUntil, Instant now) {
		Instant expiry = expiresAt(validUntil);
		String generation = verifiedAt.toString();
		Instant reminder = expiry.minus(LEAD);
		if (reminder.isAfter(now)) {
			this.deadlines.schedule(new DeadlineRequest(REMINDER.key(), accountId, generation, null, reminder));
		}
		this.deadlines.schedule(new DeadlineRequest(EXPIRY.key(), accountId, generation, null, expiry));
	}

	/** The account's verification as of the Deadline's generation, if it is still that one. */
	static Optional<LocalDate> sameVerification(JdbcClient jdbc, DueDeadline deadline) {
		record Row(Instant verifiedAt, LocalDate validUntil) {
		}
		return jdbc.sql("SELECT verified_at, valid_until FROM identity.student_verification WHERE account_id = :id")
			.param("id", deadline.subjectId())
			.query((rs, n) -> new Row(rs.getTimestamp("verified_at").toInstant(), rs.getObject("valid_until", LocalDate.class)))
			.optional()
			.filter(row -> row.verifiedAt().toString().equals(deadline.generation()))
			.map(Row::validUntil);
	}

	/** {@code identity.verification_expiring}, in-app and by email, with the last valid day. */
	@Component
	static class Reminder implements DeadlineHandler {

		private final JdbcClient jdbc;

		private final Notifications notifications;

		Reminder(JdbcClient jdbc, Notifications notifications) {
			this.jdbc = jdbc;
			this.notifications = notifications;
		}

		@Override
		public DeadlineKind kind() {
			return REMINDER;
		}

		@Override
		public DeadlineOutcome handle(DueDeadline deadline) {
			Optional<LocalDate> validUntil = sameVerification(this.jdbc, deadline);
			if (validUntil.isEmpty()) {
				return DeadlineOutcome.OBSOLETE;
			}
			this.notifications.send(new NotificationRequest(VERIFICATION_EXPIRING, deadline.id(),
					List.of(new Recipient(deadline.subjectId(), Audience.SUBJECT)),
					Map.of("date", validUntil.get().format(DATE)), SignIns.VERIFICATION_PATH, null, deadline.subjectId()));
			return DeadlineOutcome.FIRED;
		}

	}

	/**
	 * The expiry: {@code isVerified} is false from now on, so only the Student is told, the
	 * {@code identity.verify_student} to-do opens again, and the change is audited.
	 */
	@Component
	static class Expiry implements DeadlineHandler {

		private final JdbcClient jdbc;

		private final Notifications notifications;

		private final Todos todos;

		private final AuditLog auditLog;

		Expiry(JdbcClient jdbc, Notifications notifications, Todos todos, AuditLog auditLog) {
			this.jdbc = jdbc;
			this.notifications = notifications;
			this.todos = todos;
			this.auditLog = auditLog;
		}

		@Override
		public DeadlineKind kind() {
			return EXPIRY;
		}

		@Override
		public DeadlineOutcome handle(DueDeadline deadline) {
			UUID account = deadline.subjectId();
			// The lock StudentVerifications takes: a renewal confirmed at this moment runs before or after.
			this.jdbc.sql("SELECT id FROM identity.account WHERE id = :id FOR UPDATE")
				.param("id", account)
				.query(UUID.class)
				.optional();
			if (sameVerification(this.jdbc, deadline).isEmpty()) {
				return DeadlineOutcome.OBSOLETE;
			}
			this.notifications.send(new NotificationRequest(VERIFICATION_EXPIRED, deadline.id(),
					List.of(new Recipient(account, Audience.SUBJECT)), Map.of(), SignIns.VERIFICATION_PATH, null, account));
			this.todos.open(new TodoRequest(SignIns.VERIFY_STUDENT, account, Audience.SUBJECT, account, Map.of(),
					SignIns.VERIFICATION_PATH, null, null));
			this.auditLog.record(AuditEntry.of(AuditActor.system(), "identity.verification_expired",
					AuditObject.of("identity.account", account)));
			return DeadlineOutcome.FIRED;
		}

	}

}
