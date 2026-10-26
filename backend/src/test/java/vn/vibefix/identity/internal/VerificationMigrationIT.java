package vn.vibefix.identity.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.PreparedStatement;
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
 * Story 19.4's forward migration on a database that holds school email addresses in clear text: the
 * verifications, the codes, and the per-address counters are emptied, not carried over (SQL cannot compute
 * the keyed HMACs), the student details typed before are cleared, and afterwards no table holds an address.
 */
class VerificationMigrationIT {

	private static final PostgresTestDatabase database = PostgresTestDatabase.instance();

	private static final String BEFORE = "202610261000";

	private static final String MINH = "00000000-0000-7000-8000-00000000b001";

	private static final String ADDRESS = "minh.migration@sis.hust.edu.vn";

	@Test
	void theClearTextAddressesGoAndTheNewTablesAreEmpty() throws Exception {
		String db = database.createScratchDatabase();
		flyway(db, BEFORE).migrate();
		execute(db, """
				INSERT INTO identity.account (id, kind, github_user_id, github_login, role, created_at)
				VALUES ('%s', 'USER', 7101, 'minh', 'STUDENT', now())
				""".formatted(MINH), """
				INSERT INTO identity.profile (account_id, display_name, student_name, student_school, student_year, updated_at)
				VALUES ('%s', 'Minh', 'Trần Quang Minh', 'Đại học Bách khoa Hà Nội', 3, now())
				""".formatted(MINH), """
				INSERT INTO identity.student_verification (account_id, method, school_email, verified_at, valid_until)
				VALUES ('%s', 'SCHOOL_EMAIL', '%s', now(), DATE '2027-09-30')
				""".formatted(MINH, ADDRESS), """
				INSERT INTO identity.school_email_code (id, account_id, email, code_hash, sent_at, expires_at)
				VALUES (gen_random_uuid(), '%s', '%s', repeat('c', 64), now(), now())
				""".formatted(MINH, ADDRESS), """
				INSERT INTO identity.school_email_send_day (address_hash, day, sends) VALUES (repeat('d', 64), current_date, 2)
				""");

		flyway(db, "latest").migrate();

		assertThat(rows(db, """
				SELECT coalesce(to_regclass('identity.student_verification')::text, 'gone') || ' '
				       || (SELECT count(*) FROM identity.verification) || ' ' || (SELECT count(*) FROM identity.school_email_code)
				       || ' ' || (SELECT count(*) FROM identity.school_email_send_day)
				       || ' ' || (SELECT count(*) FROM identity.school_email_send_hour)
				"""))
			.containsExactly("gone 0 0 0 0");
		assertThat(rows(db, """
				SELECT display_name || ' ' || coalesce(student_name, '-') || ' ' || coalesce(student_school, '-') || ' '
				       || coalesce(student_year::text, '-') || ' ' || coalesce(student_major, '-')
				FROM identity.profile
				""")).containsExactly("Minh - - - -");
		assertThat(rows(db, """
				SELECT table_name || '.' || column_name FROM information_schema.columns
				WHERE table_schema = 'identity' AND table_name IN ('school_email_code', 'school_email_send_day')
				  AND column_name IN ('email', 'address_hash', 'address_hmac', 'replaced_at', 'consumed_at')
				ORDER BY 1
				""")).containsExactly("school_email_code.address_hmac", "school_email_send_day.address_hmac");
		assertThat(tablesHolding(db, ADDRESS)).isEmpty();
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

	/** Every table with a row whose text holds {@code value}, ignoring letter case. */
	private static List<String> tablesHolding(String db, String value) throws SQLException {
		List<String> holding = new ArrayList<>();
		try (Connection connection = database.superuserConnection(db)) {
			for (String table : rows(db, """
					SELECT quote_ident(table_schema) || '.' || quote_ident(table_name) FROM information_schema.tables
					WHERE table_schema NOT IN ('pg_catalog', 'information_schema') AND table_type = 'BASE TABLE'
					""")) {
				try (PreparedStatement statement = connection
					.prepareStatement("SELECT count(*) FROM " + table + " t WHERE t::text ILIKE ?")) {
					statement.setString(1, "%" + value + "%");
					try (ResultSet count = statement.executeQuery()) {
						count.next();
						if (count.getLong(1) > 0) {
							holding.add(table);
						}
					}
				}
			}
		}
		return holding;
	}

}
