package vn.vibefix.notification.internal;

import java.sql.Date;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The daily SMTP budget (AD-34), a PostgreSQL counter per UTC day, the provider's day. A send takes
 * one unit before it goes out and gives it back when nothing was sent. The send that crosses the
 * alert share marks the day once: that mark is the {@code notification.email_budget_80} alert, which
 * reaches Administrators with the notifications (Stories 1.7 and 1.11).
 */
@Component
class EmailBudget {

	static final String ALERT = "notification.email_budget_80";

	private static final Logger log = LoggerFactory.getLogger(EmailBudget.class);

	private final JdbcClient jdbc;

	private final TransactionTemplate transactions;

	private final EmailProperties properties;

	private final Clock clock;

	EmailBudget(JdbcClient jdbc, PlatformTransactionManager transactionManager, EmailProperties properties,
			Clock clock) {
		this.jdbc = jdbc;
		this.transactions = new TransactionTemplate(transactionManager);
		this.properties = properties;
		this.clock = clock;
	}

	/** Takes one unit of today's budget; empty when it is used up. */
	Optional<LocalDate> take() {
		Instant now = this.clock.instant();
		LocalDate day = LocalDate.ofInstant(now, ZoneOffset.UTC);
		Integer sent = this.transactions.execute(status -> {
			this.jdbc.sql("INSERT INTO notification.email_budget_day (day, sent) VALUES (:day, 0) ON CONFLICT DO NOTHING")
				.param("day", Date.valueOf(day))
				.update();
			Optional<Integer> taken = this.jdbc.sql("""
					UPDATE notification.email_budget_day SET sent = sent + 1
					WHERE day = :day AND sent < :budget
					RETURNING sent
					""").param("day", Date.valueOf(day)).param("budget", this.properties.dailyBudget()).query(Integer.class)
				.optional();
			if (taken.isPresent() && taken.get() >= this.properties.alertThreshold()) {
				int marked = this.jdbc.sql("""
						UPDATE notification.email_budget_day SET alerted_at = :now
						WHERE day = :day AND alerted_at IS NULL
						""").param("now", Timestamp.from(now)).param("day", Date.valueOf(day)).update();
				if (marked == 1) {
					log.warn("{}: {} of today's {} emails are used", ALERT, taken.get(), this.properties.dailyBudget());
				}
			}
			return taken.orElse(null);
		});
		return sent == null ? Optional.empty() : Optional.of(day);
	}

	/** Gives back a unit taken on {@code day} for an email that was not sent. */
	void giveBack(LocalDate day) {
		this.transactions.executeWithoutResult(status -> this.jdbc
			.sql("UPDATE notification.email_budget_day SET sent = sent - 1 WHERE day = :day AND sent > 0")
			.param("day", Date.valueOf(day))
			.update());
	}

	/** When the budget starts again: the next UTC midnight. */
	Instant nextDay() {
		return LocalDate.ofInstant(this.clock.instant(), ZoneOffset.UTC).plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant();
	}

}
