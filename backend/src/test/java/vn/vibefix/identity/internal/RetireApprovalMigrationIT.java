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
 * Story 19.7's forward migrations on a database that still holds Mentor applications, decisions, student
 * cards, and their Deadlines, notifications, to-dos, and files: the tables and the approval column are gone,
 * a live card verification ends with its projection, every row of the removed kinds and types is gone, the
 * stored files are marked deleted, and everything else, the audit trail included, is untouched.
 */
class RetireApprovalMigrationIT {

	private static final PostgresTestDatabase database = PostgresTestDatabase.instance();

	private static final String BEFORE = "202610261100";

	private static final String MENTOR = "00000000-0000-7000-8000-00000000b001";

	private static final String CARDED = "00000000-0000-7000-8000-00000000b002";

	private static final String EMAILED = "00000000-0000-7000-8000-00000000b003";

	private static final String ADMIN = "00000000-0000-7000-8000-00000000b004";

	private static final String DECISION = "00000000-0000-7000-8000-00000000b101";

	private static final String CARD_FILE = "00000000-0000-7000-8000-00000000b201";

	private static final String DOCUMENT_FILE = "00000000-0000-7000-8000-00000000b202";

	private static final String SCREENSHOT_FILE = "00000000-0000-7000-8000-00000000b203";

	@Test
	void theApprovalTablesAndTheirRowsOfEveryOtherModuleAreRetired() throws Exception {
		String db = database.createScratchDatabase();
		flyway(db, BEFORE).migrate();
		execute(db, """
				INSERT INTO identity.account (id, kind, github_user_id, github_login, role, mentor_approval_status, created_at)
				VALUES ('%s', 'USER', 8001, 'hung', 'MENTOR', 'APPROVED', now()),
				       ('%s', 'USER', 8002, 'vy', 'STUDENT', NULL, now()),
				       ('%s', 'USER', 8003, 'minh', 'STUDENT', NULL, now())
				""".formatted(MENTOR, CARDED, EMAILED), """
				INSERT INTO identity.account (id, kind, role, created_at) VALUES ('%s', 'ADMINISTRATOR', 'ADMINISTRATOR', now())
				""".formatted(ADMIN), """
				INSERT INTO identity.mentor_decision (id, account_id, decision, decided_by, decided_at)
				VALUES ('%s', '%s', 'APPROVED', '%s', now())
				""".formatted(DECISION, MENTOR, ADMIN), """
				INSERT INTO identity.mentor_application (account_id, document_file_id, document_name, document_media_type,
				    document_size, decision_id, updated_at)
				VALUES ('%s', '%s', 'cv.pdf', 'application/pdf', 100, '%s', now())
				""".formatted(MENTOR, DOCUMENT_FILE, DECISION), """
				INSERT INTO identity.student_card (id, account_id, file_id, file_name, media_type, status, uploaded_at)
				VALUES (gen_random_uuid(), '%s', '%s', 'the.pdf', 'application/pdf', 'PENDING', now())
				""".formatted(CARDED, CARD_FILE), """
				INSERT INTO identity.verification (id, account_id, kind, provider, subject_hmac, key_version, facts,
				    consent_version, verified_at, valid_until, ended_at, ended_reason)
				VALUES (gen_random_uuid(), '%1$s', 'STUDENT', 'STUDENT_CARD', repeat('a', 64), 1,
				        '{"school":"Trường thử","verifiedMonth":"2026-01"}', NULL, now(), DATE '2026-09-30', NULL, NULL),
				       (gen_random_uuid(), '%1$s', 'STUDENT', 'STUDENT_CARD', repeat('b', 64), 1, NULL, NULL,
				        now() - INTERVAL '1 year', DATE '2025-09-30', now() - INTERVAL '1 month', 'EXPIRED'),
				       (gen_random_uuid(), '%2$s', 'STUDENT', 'EMAIL_CODE', repeat('c', 64), 1,
				        '{"school":"hust.edu.vn","verifiedMonth":"2026-01"}', '1.0', now(), DATE '2026-09-30', NULL, NULL)
				""".formatted(CARDED, EMAILED), """
				INSERT INTO identity.profile (account_id, student_school, updated_at)
				VALUES ('%s', 'Trường thử', now()), ('%s', 'hust.edu.vn', now())
				""".formatted(CARDED, EMAILED), """
				INSERT INTO timers.deadline (id, kind, owner, subject_id, generation, pausable, status, due_at, created_at,
				    finished_at)
				VALUES (gen_random_uuid(), 'identity.mentor_reapply_wait', 'identity', '%1$s', 'g', FALSE, 'PENDING', now(),
				        now(), NULL),
				       (gen_random_uuid(), 'identity.mentor_application_purge', 'identity', '%1$s', 'g', FALSE, 'PENDING',
				        now(), now(), NULL),
				       (gen_random_uuid(), 'identity.mentor_document_deletion', 'identity', '%1$s', 'g', FALSE, 'FIRED', NULL,
				        now(), now()),
				       (gen_random_uuid(), 'identity.student_card_deletion', 'identity', '%2$s', 'g', FALSE, 'PENDING', now(),
				        now(), NULL),
				       (gen_random_uuid(), 'identity.verification_expiry', 'identity', '%2$s', 'g', FALSE, 'PENDING', now(),
				        now(), NULL)
				""".formatted(MENTOR, CARDED), """
				INSERT INTO notification.notification (id, account_id, type, causing_record_id, params, link, created_at)
				VALUES (gen_random_uuid(), '%1$s', 'identity.mentor_approved', gen_random_uuid(), '{}', '/', now()),
				       (gen_random_uuid(), '%1$s', 'identity.mentor_approval_revoked', gen_random_uuid(), '{}', '/', now()),
				       (gen_random_uuid(), '%2$s', 'identity.student_card_rejected', gen_random_uuid(), '{}', '/', now()),
				       (gen_random_uuid(), '%2$s', 'identity.verification_revoked', gen_random_uuid(), '{}', '/', now()),
				       (gen_random_uuid(), '%1$s', 'identity.account_locked', gen_random_uuid(), '{}', '/', now())
				""".formatted(MENTOR, CARDED), """
				INSERT INTO notification.todo (id, todo_key, account_id, subject_id, tier, params, link, opened_at)
				VALUES (gen_random_uuid(), 'identity.complete_mentor_application', '%1$s', '%1$s', 1, '{}', '/', now()),
				       (gen_random_uuid(), 'identity.link_github', '%1$s', '%1$s', 1, '{}', '/', now())
				""".formatted(MENTOR), """
				INSERT INTO files.stored_file (id, purpose, account_id, file_name, media_type, kind, size_bytes, sha256,
				    stored_at)
				VALUES ('%s', 'identity.student_card', '%s', 'the.pdf', 'application/pdf', 'PDF', 100, repeat('d', 64), now()),
				       ('%s', 'identity.mentor_document', '%s', 'cv.pdf', 'application/pdf', 'PDF', 100, repeat('e', 64), now()),
				       ('%s', 'job.screenshot', '%s', 'man-hinh.png', 'image/png', 'IMAGE', 100, repeat('f', 64), now())
				""".formatted(CARD_FILE, CARDED, DOCUMENT_FILE, MENTOR, SCREENSHOT_FILE, EMAILED), """
				INSERT INTO audit.audit_entry (id, occurred_at, actor_type, actor_account_id, action)
				VALUES (gen_random_uuid(), now(), 'USER', '%s', 'identity.mentor_approved')
				""".formatted(ADMIN));

		flyway(db, "latest").migrate();

		assertThat(rows(db, """
				SELECT coalesce(to_regclass('identity.mentor_application')::text, 'gone') || ' '
				       || coalesce(to_regclass('identity.mentor_decision')::text, 'gone') || ' '
				       || coalesce(to_regclass('identity.student_card')::text, 'gone')
				""")).containsExactly("gone gone gone");
		assertThat(rows(db, """
				SELECT count(*) FROM information_schema.columns
				WHERE table_schema = 'identity' AND table_name = 'account' AND column_name = 'mentor_approval_status'
				""")).containsExactly("0");
		// The live card verification ends with the card path; the old ended one and the email one stay.
		assertThat(rows(db, """
				SELECT account_id || ' ' || provider || ' ' || (ended_at IS NULL) FROM identity.verification
				ORDER BY account_id, provider, ended_at NULLS FIRST
				""")).containsExactly(CARDED + " STUDENT_CARD false", EMAILED + " EMAIL_CODE true");
		assertThat(rows(db, """
				SELECT account_id || ' ' || coalesce(student_school, '-') FROM identity.profile ORDER BY account_id
				""")).containsExactly(CARDED + " -", EMAILED + " hust.edu.vn");
		assertThat(rows(db, "SELECT kind FROM timers.deadline ORDER BY kind"))
			.containsExactly("identity.verification_expiry");
		assertThat(rows(db, "SELECT type FROM notification.notification ORDER BY type"))
			.containsExactly("identity.account_locked");
		assertThat(rows(db, "SELECT todo_key FROM notification.todo ORDER BY todo_key"))
			.containsExactly("identity.link_github");
		assertThat(rows(db, "SELECT purpose || ' ' || (deleted_at IS NOT NULL) FROM files.stored_file ORDER BY purpose"))
			.containsExactly("identity.mentor_document true", "identity.student_card true", "job.screenshot false");
		assertThat(rows(db, "SELECT action FROM audit.audit_entry WHERE action = 'identity.mentor_approved'"))
			.containsExactly("identity.mentor_approved");
		assertThat(rows(db, "SELECT role FROM identity.account WHERE id = '" + MENTOR + "'")).containsExactly("MENTOR");
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
