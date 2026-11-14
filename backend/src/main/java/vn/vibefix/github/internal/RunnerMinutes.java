package vn.vibefix.github.internal;

import java.sql.Date;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import vn.vibefix.github.GitHubException;
import vn.vibefix.github.GitHubRunnerMinutes;
import vn.vibefix.github.PlatformRepositories;
import vn.vibefix.github.PlatformRepositories.PlatformRepository;
import vn.vibefix.shared.IdGenerator;

/**
 * The runner's monthly GitHub Actions minutes (AD-46, D1, R30, Story 23.8b). Handover copies are the only Actions
 * runs vibefix pays for -- the J7 copy and the Final Merge run on the Customer's own account -- and GitHub Free
 * gives the organization {@value #DEFAULT_BUDGET} minutes a month, so a daily job sums what the runner has billed
 * this month and alerts past the alert share before the runner stops running.
 * <p>
 * It lists the runner's runs created since the month began in Vietnam time and adds up their billed durations
 * ({@link GitHubActionsRuns#minutesSince}). The sum is kept per month in {@code github.runner_minutes_month}, and
 * the day that crosses {@value #ALERT_PERCENT}% of the budget marks the month once: that mark is
 * {@link GitHubRunnerMinutes}, which {@code admin} turns into {@code github.runner_minutes_80} for every
 * Administrator, exactly as the daily email budget's alert works. GitHub not answering leaves the month as it was
 * and is tried again the next day.
 */
@Component
class RunnerMinutes {

	/** GitHub Free's monthly Actions minutes for an organization (D1). */
	static final int DEFAULT_BUDGET = 2000;

	/** The share of the budget that alerts, as EXPERIENCE.md's {@code github.runner_minutes_80} names it. */
	static final int ALERT_PERCENT = 80;

	private static final ZoneId VIETNAM = ZoneId.of("Asia/Ho_Chi_Minh");

	private static final Logger log = LoggerFactory.getLogger(RunnerMinutes.class);

	private final JdbcClient jdbc;

	private final GitHubActionsRuns runs;

	private final PlatformRepositories repositories;

	private final ApplicationEventPublisher events;

	private final TransactionTemplate transactions;

	private final IdGenerator ids;

	private final Clock clock;

	private final int budget;

	RunnerMinutes(JdbcClient jdbc, GitHubActionsRuns runs, PlatformRepositories repositories,
			ApplicationEventPublisher events, PlatformTransactionManager transactionManager, IdGenerator ids, Clock clock,
			@Value("${vibefix.github.runner-minutes-budget:" + DEFAULT_BUDGET + "}") int budget) {
		if (budget <= 0) {
			throw new IllegalStateException("vibefix.github.runner-minutes-budget is a number of minutes, not " + budget);
		}
		this.jdbc = jdbc;
		this.runs = runs;
		this.repositories = repositories;
		this.events = events;
		this.transactions = new TransactionTemplate(transactionManager);
		this.ids = ids;
		this.clock = clock;
		this.budget = budget;
	}

	/** This month's minutes as last summed, for a test and for a caller that only reads. */
	record Month(LocalDate month, int used, int budget, int runs, boolean alerted) {
	}

	/**
	 * Sums the month's minutes and alerts once past the alert share.
	 * @return the month as it now stands, or empty when no runner is configured or GitHub did not answer
	 */
	Optional<Month> daily() {
		PlatformRepository runner = this.repositories.runner().orElse(null);
		if (runner == null) {
			// An unset or unreadable runner is an organization difference the 10-minute sweep already alerts (AD-12).
			log.debug("The runner's Actions minutes were not summed: no runner is configured");
			return Optional.empty();
		}
		Instant now = this.clock.instant();
		LocalDate month = LocalDate.ofInstant(now, VIETNAM).withDayOfMonth(1);
		GitHubActionsRuns.Minutes minutes;
		try {
			minutes = this.runs.minutesSince(runner.id(), runner.fullName(), month.atStartOfDay(VIETNAM).toInstant());
		}
		catch (GitHubException unavailable) {
			log.warn("The runner's Actions minutes for {} were not read: HTTP {}; the next day tries again", month,
					unavailable.status());
			return Optional.empty();
		}
		int used = (int) Math.min(Integer.MAX_VALUE, minutes.minutes());
		return Optional.of(record(month, used, minutes.runs(), now));
	}

	/** One transaction: the month's sum, then the mark that alerts, which only the first crossing writes. */
	private Month record(LocalDate month, int used, int runs, Instant now) {
		Month stored = this.transactions.execute(status -> {
			this.jdbc.sql("""
					INSERT INTO github.runner_minutes_month (month, used_minutes, budget, runs, read_at)
					VALUES (:month, :used, :budget, :runs, :now)
					ON CONFLICT (month) DO UPDATE SET used_minutes = EXCLUDED.used_minutes, budget = EXCLUDED.budget,
					    runs = EXCLUDED.runs, read_at = EXCLUDED.read_at
					""")
				.param("month", Date.valueOf(month))
				.param("used", used)
				.param("budget", this.budget)
				.param("runs", runs)
				.param("now", Timestamp.from(now))
				.update();
			boolean alerted = false;
			if (used * 100L >= (long) this.budget * ALERT_PERCENT) {
				alerted = this.jdbc.sql("""
						UPDATE github.runner_minutes_month SET alerted_at = :now WHERE month = :month AND alerted_at IS NULL
						""").param("now", Timestamp.from(now)).param("month", Date.valueOf(month)).update() == 1;
				if (alerted) {
					log.warn("github.runner_minutes_80: the runner has used {} of this month's {} GitHub Actions minutes",
							used, this.budget);
					this.events.publishEvent(new GitHubRunnerMinutes(this.ids.newId(), month, used, this.budget));
				}
			}
			return new Month(month, used, this.budget, runs, alerted);
		});
		return stored == null ? new Month(month, used, this.budget, runs, false) : stored;
	}

	/**
	 * The month's row as the last daily sum left it. No screen reads it: the alert's own text carries the figures,
	 * so this answers the tests, and a later reader inside {@code github}.
	 */
	Optional<Month> month(LocalDate month) {
		return this.jdbc.sql("""
				SELECT month, used_minutes, budget, runs, alerted_at FROM github.runner_minutes_month WHERE month = :month
				""")
			.param("month", Date.valueOf(month))
			.query((rs, n) -> new Month(rs.getObject("month", LocalDate.class), rs.getInt("used_minutes"),
					rs.getInt("budget"), rs.getInt("runs"), rs.getTimestamp("alerted_at") != null))
			.optional();
	}

	@Component
	@ConditionalOnBooleanProperty(name = "vibefix.scheduling.enabled", matchIfMissing = true)
	static class Scheduler {

		private final RunnerMinutes minutes;

		Scheduler(RunnerMinutes minutes) {
			this.minutes = minutes;
		}

		@Scheduled(cron = "${vibefix.github.runner-minutes-cron:0 30 4 * * *}", zone = "Asia/Ho_Chi_Minh")
		void run() {
			this.minutes.daily();
		}

	}

}
