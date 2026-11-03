package vn.vibefix.job.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;

import vn.vibefix.support.PostgresTestDatabase;

/**
 * Story 22.3's forward migration on a database from before it (AD-24, AD-40): a split keeps when its Milestone was funded, and
 * each party their active interval, from the rows a Product deletion would take, the interval ending at the release at the
 * latest; a split whose rows went keeps none.
 */
class MilestoneSplitIntervalMigrationIT {

	private static final PostgresTestDatabase database = PostgresTestDatabase.instance();

	private static final String BEFORE = "202611031010";

	private static final String FUNDED = "2026-10-20 03:00:00+00";

	private static final String ACTIVATED = "2026-10-21 03:00:00+00";

	private static final String RELEASED = "2026-10-25 03:00:00+00";

	private static final String LATER = "2026-10-28 03:00:00+00";

	@Test
	void aSplitAndItsPartiesKeepTheirIntervalsFromTheRowsThatStillExist() throws Exception {
		String db = database.createScratchDatabase();
		flyway(db, BEFORE).migrate();
		UUID job = UUID.randomUUID();
		UUID milestone = UUID.randomUUID();
		UUID split = UUID.randomUUID();
		UUID member = UUID.randomUUID();
		UUID leaver = UUID.randomUUID();
		UUID gone = UUID.randomUUID();
		execute(db, """
				INSERT INTO job.job (id, product_id, customer_account_id, state, title, problem, expected, app_users, job_types, budget,
				    deadline, rules, selection_days, opened_at, lapses_at, mentor_account_id, selected_application_id, selected_at, version,
				    created_at, updated_at, agreement_version_no)
				VALUES ('%s', gen_random_uuid(), gen_random_uuid(), 'ACTIVE', 'Việc', 'Lỗi', 'Đúng', 'Khách', ARRAY['ASSESSMENT'], 'UNDER_2M',
				    DATE '2026-12-01', '{}'::jsonb, 14, now(), now(), gen_random_uuid(), gen_random_uuid(), now(), 5, now(), now(), 1)
				""".formatted(job), """
				INSERT INTO job.milestone (id, job_id, position, title, price_vnd, revision_rounds, created_at, state, funded_at,
				    crew_terms_version_no, accepted_at)
				VALUES ('%s', '%s', 0, 'Giai đoạn 1', 1000, 2, now(), 'ACCEPTED', '%s', 1, '%s')
				""".formatted(milestone, job, FUNDED, RELEASED), """
				INSERT INTO job.crew_row (id, job_id, member_account_id, status, signed_at, activated_at, window_end, released_at,
				    released_reason, created_at)
				VALUES (gen_random_uuid(), '%1$s', '%2$s', 'RELEASED', '%3$s', '%3$s', '%4$s', '%4$s', 'LEFT', now())
				""".formatted(job, leaver, ACTIVATED, LATER), """
				INSERT INTO job.crew_row (id, job_id, member_account_id, status, signed_at, activated_at, created_at)
				VALUES (gen_random_uuid(), '%1$s', '%2$s', 'ACTIVE', '%3$s', '%3$s', now())
				""".formatted(job, member, ACTIVATED), """
				INSERT INTO job.milestone_split (id, job_id, product_id, milestone_id, milestone_number, mentor_account_id, status,
				    cap_applies, released_at, draft_due_at, created_at)
				VALUES ('%s', '%s', gen_random_uuid(), '%s', 1, gen_random_uuid(), 'AWAITING_DRAFT', true, '%s', '%s', '%s')
				""".formatted(split, job, milestone, RELEASED, LATER, RELEASED), """
				INSERT INTO job.milestone_split_party (id, split_id, party_account_id, is_mentor, whole_interval, active_at_release, position)
				VALUES (gen_random_uuid(), '%1$s', gen_random_uuid(), true, true, true, 0),
				       (gen_random_uuid(), '%1$s', '%2$s', false, false, true, 1),
				       (gen_random_uuid(), '%1$s', '%3$s', false, false, false, 2)
				""".formatted(split, member, leaver), """
				INSERT INTO job.milestone_split (id, job_id, product_id, milestone_id, milestone_number, mentor_account_id, status,
				    cap_applies, released_at, draft_due_at, created_at)
				VALUES ('%s', gen_random_uuid(), gen_random_uuid(), gen_random_uuid(), 2, gen_random_uuid(), 'AWAITING_DRAFT', true, '%s', '%s', '%s')
				""".formatted(gone, RELEASED, LATER, RELEASED), """
				INSERT INTO job.milestone_split_party (id, split_id, party_account_id, is_mentor, whole_interval, active_at_release, position)
				VALUES (gen_random_uuid(), '%1$s', gen_random_uuid(), true, true, true, 0)
				""".formatted(gone));

		flyway(db, "latest").migrate();

		assertThat(rows(db, "SELECT funded_at = '" + FUNDED + "' FROM job.milestone_split WHERE id = '" + split + "'")).containsExactly("t");
		assertThat(rows(db, """
				SELECT position || ':' || (active_from = '%s') || ':' || (active_to = '%s')
				FROM job.milestone_split_party WHERE split_id = '%s' AND position = 0
				""".formatted(FUNDED, RELEASED, split))).as("the Mentor: the Milestone's interval").containsExactly("0:true:true");
		assertThat(rows(db, """
				SELECT position || ':' || (active_from = '%s') || ':' || (active_to = '%s')
				FROM job.milestone_split_party WHERE split_id = '%s' AND position > 0 ORDER BY position
				""".formatted(ACTIVATED, RELEASED, split))).as("the member, and one who left after the release")
			.containsExactly("1:true:true", "2:true:true");
		assertThat(rows(db, "SELECT funded_at IS NULL FROM job.milestone_split WHERE id = '" + gone + "'")).containsExactly("t");
		assertThat(rows(db, "SELECT active_from IS NULL FROM job.milestone_split_party WHERE split_id = '" + gone + "'"))
			.containsExactly("t");
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
