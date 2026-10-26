package vn.vibefix.identity.internal;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;

import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * The {@code identity} code-expiry purge (AD-7, AD-24): deletes school email codes once past their
 * {@code expires_at}, the per-address counters of past UTC days, and the per-account counters of past
 * clock hours, since by then neither a limit nor an entry needs them; and the verification rows that
 * ended more than {@value #ENDED_ROWS_KEPT_MONTHS} months ago, whose subject HMACs have served their
 * retention. It runs every 10 minutes.
 */
@Component
class VerificationCodePurge {

	/** How long an ended verification keeps its subject HMAC, as an erased account's signals are kept. */
	static final int ENDED_ROWS_KEPT_MONTHS = PersonSignalPurge.MONTHS_KEPT;

	private final JdbcClient jdbc;

	private final Clock clock;

	private final VerificationRows rows;

	VerificationCodePurge(JdbcClient jdbc, Clock clock, VerificationRows rows) {
		this.jdbc = jdbc;
		this.clock = clock;
		this.rows = rows;
	}

	/** @return how many rows were deleted */
	int purge() {
		Instant now = this.clock.instant();
		int codes = this.jdbc.sql("DELETE FROM identity.school_email_code WHERE expires_at <= :now")
			.param("now", Timestamp.from(now))
			.update();
		int days = this.jdbc.sql("DELETE FROM identity.school_email_send_day WHERE day < :today")
			.param("today", LocalDate.ofInstant(now, ZoneOffset.UTC))
			.update();
		int hours = this.jdbc.sql("DELETE FROM identity.school_email_send_hour WHERE hour < :hour")
			.param("hour", Timestamp.from(now.truncatedTo(ChronoUnit.HOURS)))
			.update();
		int ended = this.rows.purgeEnded(now.atOffset(ZoneOffset.UTC).minusMonths(ENDED_ROWS_KEPT_MONTHS).toInstant());
		return codes + days + hours + ended;
	}

	@Component
	@ConditionalOnBooleanProperty(name = "vibefix.scheduling.enabled", matchIfMissing = true)
	static class Scheduler {

		private final VerificationCodePurge purge;

		Scheduler(VerificationCodePurge purge) {
			this.purge = purge;
		}

		@Scheduled(fixedDelayString = "PT10M", initialDelayString = "PT10M")
		void run() {
			this.purge.purge();
		}

	}

}
