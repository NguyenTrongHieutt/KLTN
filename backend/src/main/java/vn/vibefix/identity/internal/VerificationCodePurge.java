package vn.vibefix.identity.internal;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneOffset;

import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Deletes school email codes a day after they were sent, and the per-address counters of past days
 * (AD-24): by then neither limit nor the entry needs them.
 */
@Component
class VerificationCodePurge {

	static final Duration KEPT = Duration.ofDays(1);

	private final JdbcClient jdbc;

	private final Clock clock;

	VerificationCodePurge(JdbcClient jdbc, Clock clock) {
		this.jdbc = jdbc;
		this.clock = clock;
	}

	/** @return how many rows were deleted */
	int purge() {
		int codes = this.jdbc.sql("DELETE FROM identity.school_email_code WHERE sent_at <= :cutoff")
			.param("cutoff", Timestamp.from(this.clock.instant().minus(KEPT)))
			.update();
		int counters = this.jdbc.sql("DELETE FROM identity.school_email_send_day WHERE day < :today")
			.param("today", LocalDate.ofInstant(this.clock.instant(), ZoneOffset.UTC))
			.update();
		return codes + counters;
	}

	@Component
	@ConditionalOnBooleanProperty(name = "vibefix.scheduling.enabled", matchIfMissing = true)
	static class Scheduler {

		private final VerificationCodePurge purge;

		Scheduler(VerificationCodePurge purge) {
			this.purge = purge;
		}

		@Scheduled(fixedDelayString = "PT1H", initialDelayString = "PT10M")
		void run() {
			this.purge.purge();
		}

	}

}
