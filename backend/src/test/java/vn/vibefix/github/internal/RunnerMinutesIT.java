package vn.vibefix.github.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.awaitility.Awaitility;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import vn.vibefix.identity.internal.AdministratorTestSupport;
import vn.vibefix.support.IntegrationTest;

/**
 * AD-46, D1, R30 (Story 23.8b): the runner's GitHub Actions minutes, summed daily from its own runs. Each run's
 * billed duration is rounded up to the minute, the month's sum is kept in {@code github.runner_minutes_month},
 * and the day that crosses the alert share alerts every Administrator once that month
 * ({@code github.runner_minutes_80}). GitHub not answering leaves the month as it was. GitHub is
 * {@link GitHubStub}, and the budget here is 10 minutes, so the share is crossed with a handful of runs.
 */
class RunnerMinutesIT extends IntegrationTest {

	private static final Path DIRECTORY;

	private static final GitHubStub GITHUB;

	private static final String RUNNER = "vibefix-runner";

	private static final ZoneId VIETNAM = ZoneId.of("Asia/Ho_Chi_Minh");

	static {
		try {
			DIRECTORY = Files.createTempDirectory("runner-minutes-");
			GITHUB = new GitHubStub(Files.createDirectories(DIRECTORY.resolve("git")));
			GITHUB.runner(RUNNER);
			Files.writeString(DIRECTORY.resolve("app-key"), TestKeys.pkcs1());
		}
		catch (Exception ex) {
			throw new IllegalStateException(ex);
		}
	}

	@Autowired
	private RunnerMinutes minutes;

	@Autowired
	private JdbcClient jdbc;

	@Autowired
	private ApplicationContext context;

	@DynamicPropertySource
	static void github(DynamicPropertyRegistry registry) {
		registry.add("vibefix.github.api-url", () -> GITHUB.apiUrl().toString());
		registry.add("vibefix.github.git-url", () -> GITHUB.gitUrl().toString());
		registry.add("vibefix.github.platform-app.id", () -> "123456");
		registry.add("vibefix.github.platform-app.installation-id", () -> String.valueOf(GitHubStub.INSTALLATION));
		registry.add("vibefix.github.platform-app.key-file", () -> DIRECTORY.resolve("app-key").toString());
		registry.add("vibefix.github.runner-repository", () -> RUNNER);
		registry.add("vibefix.github.runner-minutes-budget", () -> "10");
	}

	@AfterAll
	static void stop() {
		GITHUB.close();
	}

	@BeforeEach
	void noRunsYet() {
		GITHUB.runs.clear();
		GITHUB.runListQueries.clear();
		GITHUB.runsHidden = false;
		GITHUB.extra = (exchange, request, query) -> false;
	}

	@Test
	void sumsTheMonthsRunsRoundedUpToTheMinuteAndAlertsOncePastTheShare() {
		UUID admin = AdministratorTestSupport.create(this.context, "quan.le@vibefix.test", "Lê Quân").accountId();
		// Each run is rounded up to the minute GitHub bills: 3 min 20 s twice is 8 of the month's 10 minutes.
		run(200_000);
		run(200_000);

		RunnerMinutes.Month month = this.minutes.daily().orElseThrow();

		assertThat(month.used()).isEqualTo(8);
		assertThat(month.budget()).isEqualTo(10);
		assertThat(month.runs()).isEqualTo(2);
		assertThat(month.alerted()).isTrue();
		assertThat(this.minutes.month(LocalDate.ofInstant(this.clock.instant(), VIETNAM).withDayOfMonth(1)).orElseThrow())
			.isEqualTo(month);
		assertThat(GITHUB.runListQueries).as("one page, the month's runs only, by GitHub's created filter")
			.singleElement()
			.satisfies(query -> assertThat(query).contains("created=>=" + firstOfTheMonth(), "per_page=100", "page=1"));
		awaitPublications();
		assertThat(alerts(admin)).containsExactly("8 10");

		// A second look the same month sums again and alerts no more.
		int read = GITHUB.runListQueries.size();
		run(600_000);
		RunnerMinutes.Month again = this.minutes.daily().orElseThrow();
		awaitPublications();

		assertThat(again.used()).isEqualTo(18);
		assertThat(again.alerted()).isFalse();
		assertThat(alerts(admin)).containsExactly("8 10");
		assertThat(GITHUB.runListQueries.subList(read, GITHUB.runListQueries.size())).singleElement()
			.satisfies(query -> assertThat(query).contains("page=1"));
	}

	/**
	 * A month of more runs than one page holds: the sum follows GitHub's pages, so the minutes and the run count
	 * cover every page, not only the first.
	 */
	@Test
	void aMonthOverOnePageIsSummedOverEveryPage() {
		// 100 runs of 1 minute fill page 1 exactly, and 3 of 2 minutes sit on page 2: 106 minutes over 103 runs.
		for (int run = 0; run < 100; run++) {
			run(60_000);
		}
		for (int run = 0; run < 3; run++) {
			run(90_000);
		}

		RunnerMinutes.Month month = this.minutes.daily().orElseThrow();

		assertThat(month.runs()).as("every page's runs").isEqualTo(103);
		assertThat(month.used()).as("100 x 1 minute, then 3 x 2 minutes").isEqualTo(106);
		assertThat(GITHUB.runListQueries).as("page 1 was full, so page 2 was read, and page 2 was not")
			.hasSize(2)
			.satisfies(queries -> {
				assertThat(queries.get(0)).contains("created=>=" + firstOfTheMonth(), "per_page=100", "page=1");
				assertThat(queries.get(1)).contains("per_page=100", "page=2");
			});
	}

	/** The month's first moment in Vietnam time, which GitHub's {@code created} filter names. */
	private String firstOfTheMonth() {
		return LocalDate.ofInstant(this.clock.instant(), VIETNAM).withDayOfMonth(1).atStartOfDay(VIETNAM).toInstant()
			.toString();
	}

	@Test
	void aMonthBelowTheShareIsRecordedWithoutAnAlert() {
		UUID admin = AdministratorTestSupport.create(this.context, "quan.le@vibefix.test", "Lê Quân").accountId();
		run(60_000);

		RunnerMinutes.Month month = this.minutes.daily().orElseThrow();
		awaitPublications();

		assertThat(month.used()).isOne();
		assertThat(month.alerted()).isFalse();
		assertThat(alerts(admin)).isEmpty();
	}

	/**
	 * GitHub's runs listing is not documented to carry {@code run_duration_ms} -- it belongs to
	 * {@code /runs/{id}/timing} -- so a run shaped as the listing really returns it is timed from its own
	 * {@code run_started_at} to {@code updated_at}, as {@code SpikeS6Command} times one. A run that shows neither
	 * counts as nothing, rather than failing the sum.
	 */
	@Test
	void aRunWithoutAReportedDurationIsTimedFromItsOwnTimestamps() {
		timedRun("2026-01-02T03:00:00Z", "2026-01-02T03:03:20Z");

		assertThat(this.minutes.daily().orElseThrow().used()).as("3 min 20 s, rounded up").isEqualTo(4);

		GITHUB.runs.clear();
		timedRun(null, null);

		assertThat(this.minutes.daily().orElseThrow().used()).as("neither reads").isZero();
	}

	@Test
	void githubNotAnsweringLeavesTheMonthAsItWas() {
		String runs = "/repos/" + GitHubStub.ORGANIZATION + "/" + RUNNER + "/actions/runs";
		GITHUB.extra = (exchange, request, query) -> {
			if (!"GET".equals(request.method()) || !runs.equals(request.path())) {
				return false;
			}
			GitHubStub.send(exchange, 503, "{\"message\":\"Server Error\"}");
			return true;
		};

		assertThat(this.minutes.daily()).isEmpty();
		assertThat(this.jdbc.sql("SELECT count(*) FROM github.runner_minutes_month").query(Long.class).single()).isZero();
	}

	/** The alerts every Administrator got, as "used budget". */
	private List<String> alerts(UUID admin) {
		return this.jdbc.sql("""
				SELECT (params ->> 'used') || ' ' || (params ->> 'budget') FROM notification.notification
				WHERE type = 'github.runner_minutes_80' AND account_id = :admin
				""").param("admin", admin).query(String.class).list();
	}

	/** Waits until every publication of the alert's event has been delivered, as the other ITs do. */
	private void awaitPublications() {
		Awaitility.await()
			.atMost(Duration.ofSeconds(15))
			.until(() -> this.jdbc.sql("SELECT count(*) FROM platform.event_publication WHERE completion_date IS NULL")
				.query(Long.class).single() == 0);
	}

	/** One run of the runner whose listing reports {@code run_duration_ms}. */
	private void run(long durationMs) {
		listed().put("run_duration_ms", durationMs);
	}

	/** One run of the runner as GitHub's listing really shows it: no duration, only its own timestamps. */
	private void timedRun(@Nullable String startedAt, @Nullable String updatedAt) {
		Map<String, Object> run = listed();
		if (startedAt != null) {
			run.put("run_started_at", startedAt);
		}
		if (updatedAt != null) {
			run.put("updated_at", updatedAt);
		}
	}

	/** A completed run of the runner, newly listed, for a caller to time. */
	private Map<String, Object> listed() {
		long id = GITHUB.runs.size() + 1;
		Map<String, Object> run = Collections.synchronizedMap(new LinkedHashMap<>());
		run.put("id", id);
		run.put("repository_full_name", GitHubStub.ORGANIZATION + "/" + RUNNER);
		run.put("status", "completed");
		run.put("conclusion", "success");
		GITHUB.runs.put(id, run);
		return run;
	}

}
