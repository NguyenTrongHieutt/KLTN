package vn.vibefix;

import static org.assertj.core.api.Assertions.assertThat;

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
 * Story 23.10d's two forward migrations (FR-21, FR-38, D10, AD-8, AD-21) on a database that still holds a final
 * ZIP: the stored archive's {@code files} row, a second archive row already marked deleted, another purpose's
 * file, and the Setup that named the archive through {@code repo.job_setup.archive_file_id}.
 * <p>
 * Afterwards both archive rows read deleted -- the one that was already deleted keeping the time it was marked,
 * because the migration only touches {@code deleted_at IS NULL} -- the other purpose's file is untouched, and
 * {@code repo.job_setup} has no {@code archive_file_id} column. The Setup's other columns are untouched: the
 * repositories it named, its head, its times and its own deletion all stay, because the 30-day deletion still
 * needs them and the Setup is the Job's record of what was created on GitHub.
 */
class RetireFinalZipMigrationIT {

	private static final PostgresTestDatabase database = PostgresTestDatabase.instance();

	/** The version right before this story's first migration. */
	private static final String BEFORE = "202611160903";

	/** The last of its two. */
	private static final String AFTER = "202611170901";

	private static final String ARCHIVE = "00000000-0000-7000-8000-000000010d01";

	private static final String ARCHIVE_DELETED = "00000000-0000-7000-8000-000000010d02";

	private static final String ATTACHMENT = "00000000-0000-7000-8000-000000010d03";

	private static final String SETUP = "00000000-0000-7000-8000-000000010d04";

	private static final String JOB = "00000000-0000-7000-8000-000000010d05";

	private static final String PRODUCT = "00000000-0000-7000-8000-000000010d06";

	private static final String CUSTOMER = "00000000-0000-7000-8000-000000010d07";

	/** When the second archive was marked deleted, before this story ran. */
	private static final String MARKED_EARLIER = "2026-01-02 03:04:05+00";

	private static final String HEAD = "a1b2c3d4e5f60718293a4b5c6d7e8f9012345678";

	@Test
	void theArchiveRowsAreMarkedDeletedAndTheSetupLosesItsColumnOnly() throws Exception {
		String db = database.createScratchDatabase();
		flyway(db, BEFORE).migrate();
		execute(db, """
				INSERT INTO files.stored_file (id, purpose, account_id, file_name, media_type, kind, size_bytes, sha256,
				    stored_at, deleted_at)
				VALUES ('%1$s', 'repo.final_zip', '%4$s', 'job-1-intermediary.zip', 'application/zip', 'ARCHIVE', 2048,
				        repeat('a', 64), now(), NULL),
				       ('%2$s', 'repo.final_zip', '%4$s', 'job-0-intermediary.zip', 'application/zip', 'ARCHIVE', 1024,
				        repeat('b', 64), now(), TIMESTAMPTZ '%5$s'),
				       ('%3$s', 'work.attachment', '%4$s', 'ban-giao.pdf', 'application/pdf', 'PDF', 512,
				        repeat('c', 64), now(), NULL)
				""".formatted(ARCHIVE, ARCHIVE_DELETED, ATTACHMENT, CUSTOMER, MARKED_EARLIER), """
				INSERT INTO repo.job_setup (id, job_id, product_id, status, source_full_name, default_branch, head_sha,
				    head_read_at, checked_at, approved_sha, confirmed_at, intermediary_github_id, intermediary_name,
				    intermediary_at, working_github_id, working_name, working_at, copied_at, started_at, finished_at,
				    archive_file_id, deleted_at)
				VALUES ('%1$s', '%2$s', '%3$s', 'DONE', 'lan-dev/quan-may', 'main', '%5$s', now(), now(), '%5$s', now(),
				        9901, 'vibefix-dev/job-1-intermediary', now(), 9902, 'vibefix-dev/job-1-working', now(), now(),
				        now(), now(), '%4$s', now())
				""".formatted(SETUP, JOB, PRODUCT, ARCHIVE, HEAD));

		flyway(db, AFTER).migrate();

		assertThat(rows(db, """
				SELECT file_name || ' ' || (deleted_at IS NOT NULL) FROM files.stored_file ORDER BY file_name
				""")).as("every final ZIP reads deleted; another purpose's file does not")
			.containsExactly("ban-giao.pdf false", "job-0-intermediary.zip true", "job-1-intermediary.zip true");
		assertThat(rows(db, """
				SELECT to_char(deleted_at AT TIME ZONE 'UTC', 'YYYY-MM-DD HH24:MI:SS') FROM files.stored_file
				WHERE id = '%s'
				""".formatted(ARCHIVE_DELETED))).as("a row already marked keeps the time it was marked")
			.containsExactly("2026-01-02 03:04:05");
		assertThat(rows(db, """
				SELECT count(*)::text FROM information_schema.columns
				WHERE table_schema = 'repo' AND table_name = 'job_setup' AND column_name = 'archive_file_id'
				""")).as("the column that named the archive is gone").containsExactly("0");
		assertThat(rows(db, """
				SELECT status || ' ' || source_full_name || ' ' || default_branch || ' ' || head_sha || ' '
				       || intermediary_github_id || ' ' || intermediary_name || ' ' || working_github_id || ' '
				       || working_name || ' ' || (deleted_at IS NOT NULL)
				FROM repo.job_setup
				""")).as("the Setup's other columns are untouched")
			.containsExactly("DONE lan-dev/quan-may main " + HEAD
					+ " 9901 vibefix-dev/job-1-intermediary 9902 vibefix-dev/job-1-working true");
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
