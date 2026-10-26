package vn.vibefix.identity.internal;

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
 * Story 19.3's forward migration on a database that holds the three role profiles: each account gets
 * one {@code identity.profile} row with the merged fields, the old tables are gone, and the Mentor
 * application keeps only its own columns.
 */
class ProfileMigrationIT {

	private static final PostgresTestDatabase database = PostgresTestDatabase.instance();

	private static final String BEFORE = "202610251320";

	private static final String REACT = "0192b000-0000-7000-8001-000000000004";

	private static final String DOCKER = "0192b000-0000-7000-8001-000000000034";

	private static final String LAN = "00000000-0000-7000-8000-00000000a001";

	private static final String MINH = "00000000-0000-7000-8000-00000000a002";

	private static final String VY = "00000000-0000-7000-8000-00000000a003";

	private static final String HUNG = "00000000-0000-7000-8000-00000000a004";

	private static final String DRAFT = "00000000-0000-7000-8000-00000000a005";

	@Test
	void theRoleProfilesBecomeOneProfileRowPerAccount() throws Exception {
		String db = database.createScratchDatabase();
		flyway(db, BEFORE).migrate();
		execute(db, """
				INSERT INTO identity.account (id, kind, github_user_id, github_login, role, mentor_approval_status, created_at)
				VALUES ('%s', 'USER', 7001, 'lan', 'CUSTOMER', NULL, now()),
				       ('%s', 'USER', 7002, 'minh', 'STUDENT', NULL, now()),
				       ('%s', 'USER', 7003, 'vy', 'STUDENT', NULL, now()),
				       ('%s', 'USER', 7004, 'hung', 'MENTOR', 'APPROVED', now()),
				       ('%s', 'USER', 7005, 'draft', 'MENTOR', 'PENDING', now())
				""".formatted(LAN, MINH, VY, HUNG, DRAFT), """
				INSERT INTO identity.customer_profile (account_id, kind, display_name, contact_email, phone, updated_at)
				VALUES ('%s', 'BUSINESS', 'Tiệm bánh Lan', 'lan@tiembanh.vn', '+84 912 345 678', now())
				""".formatted(LAN), """
				INSERT INTO identity.student_profile
				    (account_id, real_name, school, year_of_study, linkedin_url, show_name_and_school, skill_ids, skill_names,
				     updated_at)
				VALUES ('%s', 'Trần Quang Minh', 'Đại học Bách khoa Hà Nội', 3, 'https://www.linkedin.com/in/minh-tran', FALSE,
				        ARRAY['%s'::uuid], 'React', now()),
				       ('%s', 'Lê Thảo Vy', 'Đại học Kinh tế Quốc dân', 2, NULL, TRUE, '{}', '', now())
				""".formatted(MINH, REACT, VY), """
				INSERT INTO identity.showcase_repository (account_id, position, owner, name) VALUES ('%s', 0, 'minh', 'shop')
				""".formatted(MINH), """
				INSERT INTO identity.mentor_application
				    (account_id, display_name, experience, years_of_experience, skill_ids, skill_names, linkedin_url,
				     linkedin_hmac, linkedin_key_version, linkedin_reserved, privacy_notice_version, n22_version, submitted_at,
				     updated_at)
				VALUES ('%s', 'Đỗ Mạnh Hùng', 'Kỹ sư phần mềm 8 năm.', 8, ARRAY['%s'::uuid, '%s'::uuid], 'React, Docker',
				        'https://www.linkedin.com/in/do-manh-hung', repeat('a', 64), 1, TRUE, '3.0', '1.0', now(), now())
				""".formatted(HUNG, REACT, DOCKER), """
				INSERT INTO identity.mentor_application
				    (account_id, document_file_id, document_name, document_media_type, document_size, updated_at)
				VALUES ('%s', gen_random_uuid(), 'cv.pdf', 'application/pdf', 100, now())
				""".formatted(DRAFT));

		flyway(db, "latest").migrate();

		assertThat(rows(db, """
				SELECT account_id || '|' || coalesce(display_name, '-') || '|' || kind || '|' || coalesce(contact_email, '-')
				       || '|' || coalesce(phone, '-') || '|' || coalesce(experience, '-') || '|'
				       || coalesce(years_of_experience::text, '-') || '|' || array_to_string(skill_ids, ',') || '|' || skill_names
				       || '|' || coalesce(student_name, '-') || '|' || coalesce(student_school, '-') || '|'
				       || coalesce(student_year::text, '-') || '|' || hide_student_details
				FROM identity.profile ORDER BY account_id
				""")).containsExactly(
				LAN + "|Tiệm bánh Lan|BUSINESS|lan@tiembanh.vn|+84 912 345 678|-|-|||-|-|-|false",
				MINH + "|-|INDIVIDUAL|-|-|-|-|" + REACT + "|React|Trần Quang Minh|Đại học Bách khoa Hà Nội|3|true",
				VY + "|-|INDIVIDUAL|-|-|-|-|||Lê Thảo Vy|Đại học Kinh tế Quốc dân|2|false",
				HUNG + "|Đỗ Mạnh Hùng|INDIVIDUAL|-|-|Kỹ sư phần mềm 8 năm.|8|" + REACT + "," + DOCKER
						+ "|React, Docker|-|-|-|false");
		assertThat(rows(db, "SELECT owner || '/' || name FROM identity.showcase_repository")).containsExactly("minh/shop");
		assertThat(rows(db, """
				SELECT coalesce(to_regclass('identity.customer_profile')::text, 'gone') || ' '
				       || coalesce(to_regclass('identity.student_profile')::text, 'gone')
				""")).containsExactly("gone gone");
		assertThat(rows(db, """
				SELECT column_name FROM information_schema.columns
				WHERE table_schema = 'identity' AND table_name = 'mentor_application'
				  AND column_name IN ('display_name', 'experience', 'years_of_experience', 'skill_ids', 'skill_names',
				                      'search_vector', 'linkedin_url', 'other_link', 'document_name')
				ORDER BY column_name
				""")).containsExactly("document_name", "linkedin_url", "other_link");
		// Hidden details stay out of search; shown ones are found.
		assertThat(rows(db, """
				SELECT account_id FROM identity.profile
				WHERE search_vector @@ plainto_tsquery('platform.vi_search', 'Bách khoa')
				   OR search_vector @@ plainto_tsquery('platform.vi_search', 'Kinh tế')
				""")).containsExactly(VY);
		assertThat(rows(db, "SELECT count(*) FROM identity.person_signal")).containsExactly("0");
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
