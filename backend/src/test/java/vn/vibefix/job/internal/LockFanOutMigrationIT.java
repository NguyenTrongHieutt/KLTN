package vn.vibefix.job.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;

import vn.vibefix.support.PostgresTestDatabase;

/**
 * Story 20.4's forward migrations on a database that still holds {@code INACTIVE} Crew rows and unfinished
 * publications of {@code AccountLocked} to team's removed listener: a row a lock left {@code INACTIVE} is released
 * as {@code LEFT} when it was ever activated, its window ending where it ended or at the migration, and as
 * {@code DECLINED} when it never was; the status check no longer takes {@code INACTIVE} and the released reason
 * check takes {@code LEFT}; the team listener's unfinished publications and their stalled marks go, and everything
 * else stays.
 */
class LockFanOutMigrationIT {

	private static final PostgresTestDatabase database = PostgresTestDatabase.instance();

	/** The last version before Story 20.4. */
	private static final String BEFORE = "202610271200";

	private static final String JOB = "00000000-0000-7000-8000-00000000c001";

	private static final String LEFT_OPEN = "00000000-0000-7000-8000-00000000c101";

	private static final String LEFT_ENDED = "00000000-0000-7000-8000-00000000c102";

	private static final String PICKED = "00000000-0000-7000-8000-00000000c103";

	private static final String WORKING = "00000000-0000-7000-8000-00000000c104";

	@Test
	void inactiveRowsAreReleasedAndTeamsLockPublicationsRetired() throws Exception {
		String db = database.createScratchDatabase();
		flyway(db, BEFORE).migrate();
		execute(db, """
				INSERT INTO job.job (id, product_id, customer_account_id, state, title, job_types, rules, selection_days,
				    mentor_account_id, selected_application_id, selected_at, created_at, updated_at)
				VALUES ('%s', gen_random_uuid(), gen_random_uuid(), 'ACTIVE', 'Sửa lỗi đặt bàn', ARRAY['BUG_FIX'], '{}'::jsonb,
				    14, gen_random_uuid(), gen_random_uuid(), now(), now(), now())
				""".formatted(JOB), """
				INSERT INTO job.crew_row (id, job_id, member_account_id, status, signed_at, activated_at, window_end, created_at)
				VALUES ('%2$s', '%1$s', gen_random_uuid(), 'INACTIVE', now() - INTERVAL '9 days', now() - INTERVAL '9 days',
				        NULL, now() - INTERVAL '10 days'),
				       ('%3$s', '%1$s', gen_random_uuid(), 'INACTIVE', now() - INTERVAL '9 days', now() - INTERVAL '9 days',
				        TIMESTAMPTZ '2026-10-20 03:00:00+00', now() - INTERVAL '10 days'),
				       ('%4$s', '%1$s', gen_random_uuid(), 'INACTIVE', NULL, NULL, NULL, now() - INTERVAL '10 days'),
				       ('%5$s', '%1$s', gen_random_uuid(), 'ACTIVE', now() - INTERVAL '9 days', now() - INTERVAL '9 days', NULL,
				        now() - INTERVAL '10 days')
				""".formatted(JOB, LEFT_OPEN, LEFT_ENDED, PICKED, WORKING), """
				INSERT INTO platform.event_publication (id, listener_id, event_type, serialized_event, publication_date,
				    completion_date)
				VALUES ('00000000-0000-7000-8000-00000000c301',
				        'vn.vibefix.team.internal.TeamLifecycle.onAccountLocked(vn.vibefix.identity.AccountLocked)',
				        'vn.vibefix.identity.AccountLocked', '{}', now(), NULL),
				       ('00000000-0000-7000-8000-00000000c302',
				        'vn.vibefix.team.internal.TeamLifecycle.onAccountLocked(vn.vibefix.identity.AccountLocked)',
				        'vn.vibefix.identity.AccountLocked', '{}', now(), now()),
				       ('00000000-0000-7000-8000-00000000c303',
				        'vn.vibefix.job.internal.CrewListeners.onAccountLocked(vn.vibefix.identity.AccountLocked)',
				        'vn.vibefix.identity.AccountLocked', '{}', now(), NULL)
				""", """
				INSERT INTO platform.event_publication_stalled (publication_id, stalled_at)
				VALUES ('00000000-0000-7000-8000-00000000c301', now()), ('00000000-0000-7000-8000-00000000c303', now())
				""");

		flyway(db, "latest").migrate();

		assertThat(rows(db, """
				SELECT id || ' ' || status || ' ' || coalesce(released_reason, '-') || ' ' || (released_at IS NOT NULL)
				       || ' ' || (window_end IS NOT NULL)
				FROM job.crew_row ORDER BY id
				""")).containsExactly(LEFT_OPEN + " RELEASED LEFT true true", LEFT_ENDED + " RELEASED LEFT true true",
						PICKED + " RELEASED DECLINED true false", WORKING + " ACTIVE - false false");
		assertThat(rows(db, "SELECT (window_end = released_at)::text FROM job.crew_row WHERE id = '" + LEFT_OPEN + "'"))
			.as("an open window ends at the release").containsExactly("true");
		assertThat(rows(db, "SELECT (window_end = TIMESTAMPTZ '2026-10-20 03:00:00+00')::text FROM job.crew_row WHERE id = '"
				+ LEFT_ENDED + "'")).as("an ended window keeps its end").containsExactly("true");
		assertThatThrownBy(() -> execute(db, """
				INSERT INTO job.crew_row (id, job_id, member_account_id, status, created_at)
				VALUES (gen_random_uuid(), '%s', gen_random_uuid(), 'INACTIVE', now())
				""".formatted(JOB))).as("INACTIVE is gone").isInstanceOf(SQLException.class);

		assertThat(rows(db, "SELECT id FROM platform.event_publication ORDER BY id")).containsExactly(
				"00000000-0000-7000-8000-00000000c302", "00000000-0000-7000-8000-00000000c303");
		assertThat(rows(db, "SELECT publication_id FROM platform.event_publication_stalled"))
			.containsExactly("00000000-0000-7000-8000-00000000c303");
	}

	private static Flyway flyway(String db, String target) {
		return Flyway.configure()
			.dataSource(database.jdbcUrl(db), PostgresTestDatabase.MIGRATOR, database.migratorPassword())
			.schemas("platform")
			.defaultSchema("platform")
			.createSchemas(true)
			.locations("classpath:db/migration")
			.placeholders(Map.of("runtime_role", PostgresTestDatabase.APP, "environment", "dev"))
			.target(target)
			.load();
	}

	private static void execute(String db, String... statements) throws SQLException {
		try (Connection connection = database.migratorConnection(db); Statement statement = connection.createStatement()) {
			for (String sql : statements) {
				statement.execute(sql);
			}
		}
	}

	private static List<String> rows(String db, String sql) throws SQLException {
		List<String> values = new ArrayList<>();
		try (Connection connection = database.superuserConnection(db); Statement statement = connection.createStatement();
				ResultSet result = statement.executeQuery(sql)) {
			while (result.next()) {
				values.add(result.getString(1));
			}
		}
		return values;
	}

}
