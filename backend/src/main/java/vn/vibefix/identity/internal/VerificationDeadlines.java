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
import vn.vibefix.timers.DeadlineHandler;
import vn.vibefix.timers.DeadlineKind;
import vn.vibefix.timers.DeadlineOutcome;
import vn.vibefix.timers.DeadlineRequest;
import vn.vibefix.timers.Deadlines;
import vn.vibefix.timers.DueDeadline;

/**
 * The Deadlines of a student verification (FR-5, AD-7, UX-DR16): a reminder 14 days before it
 * expires, and the expiry at the start of the day after its last valid day, in Vietnam. Both serve
 * one {@code identity.verification} row, named by their generation; once that row has ended (renewed,
 * unlinked, or erased) they are {@code OBSOLETE}. The expiry ends the row {@code EXPIRED} and
 * deletes its facts.
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

	/** Schedules the expiry of the row {@code verificationId}, and the reminder unless its time has passed already. */
	void schedule(UUID accountId, UUID verificationId, LocalDate validUntil, Instant now) {
		Instant expiry = expiresAt(validUntil);
		String generation = verificationId.toString();
		Instant reminder = expiry.minus(LEAD);
		if (reminder.isAfter(now)) {
			this.deadlines.schedule(new DeadlineRequest(REMINDER.key(), accountId, generation, null, reminder));
		}
		this.deadlines.schedule(new DeadlineRequest(EXPIRY.key(), accountId, generation, null, expiry));
	}

	/**
	 * The row the Deadline serves, while it has not ended. A generation from before Story 19.4, the time the
	 * verification was made, names no row: that verification is gone.
	 */
	static Optional<VerificationRows.Row> sameVerification(VerificationRows rows, DueDeadline deadline) {
		UUID id;
		try {
			id = UUID.fromString(deadline.generation());
		}
		catch (IllegalArgumentException notARow) {
			return Optional.empty();
		}
		return rows.liveById(id, deadline.subjectId());
	}

	/** {@code identity.verification_expiring}, in-app and by email, with the last valid day. */
	@Component
	static class Reminder implements DeadlineHandler {

		private final VerificationRows rows;

		private final Notifications notifications;

		Reminder(VerificationRows rows, Notifications notifications) {
			this.rows = rows;
			this.notifications = notifications;
		}

		@Override
		public DeadlineKind kind() {
			return REMINDER;
		}

		@Override
		public DeadlineOutcome handle(DueDeadline deadline) {
			Optional<VerificationRows.Row> row = sameVerification(this.rows, deadline);
			if (row.isEmpty()) {
				return DeadlineOutcome.OBSOLETE;
			}
			this.notifications.send(new NotificationRequest(VERIFICATION_EXPIRING, deadline.id(),
					List.of(new Recipient(deadline.subjectId(), Audience.SUBJECT)),
					Map.of("date", row.get().validUntil().format(DATE)), SignIns.VERIFICATION_PATH, null,
					deadline.subjectId()));
			return DeadlineOutcome.FIRED;
		}

	}

	/**
	 * The expiry: {@code isVerified} has been false since the start of the day, so the row ends
	 * {@code EXPIRED}, its facts and the profile's projection of them are deleted, only the account is
	 * told, and the change is audited. No to-do opens: the student verification is optional and no account role
	 * marks a Student who would be asked again (Story 20.3).
	 */
	@Component
	static class Expiry implements DeadlineHandler {

		private final JdbcClient jdbc;

		private final VerificationRows rows;

		private final Notifications notifications;

		private final AuditLog auditLog;

		Expiry(JdbcClient jdbc, VerificationRows rows, Notifications notifications, AuditLog auditLog) {
			this.jdbc = jdbc;
			this.rows = rows;
			this.notifications = notifications;
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
			Optional<VerificationRows.Row> row = sameVerification(this.rows, deadline);
			if (row.isEmpty()) {
				return DeadlineOutcome.OBSOLETE;
			}
			this.rows.end(row.get(), VerificationRows.Ending.EXPIRED, deadline.dueAt());
			this.notifications.send(new NotificationRequest(VERIFICATION_EXPIRED, deadline.id(),
					List.of(new Recipient(account, Audience.SUBJECT)), Map.of(), SignIns.VERIFICATION_PATH, null, account));
			this.auditLog.record(AuditEntry.of(AuditActor.system(), "identity.verification_expired",
					AuditObject.of("identity.account", account)));
			return DeadlineOutcome.FIRED;
		}

	}

}
