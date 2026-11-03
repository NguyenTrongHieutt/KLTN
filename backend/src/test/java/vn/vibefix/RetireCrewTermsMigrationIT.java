package vn.vibefix;

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
import org.flywaydb.core.api.FlywayException;
import org.junit.jupiter.api.Test;

import vn.vibefix.support.PostgresTestDatabase;

/**
 * Story 22.4's forward migrations on a database that still holds share Team Issues, open and decided, their holds, the
 * Crew Terms with the M1 pin, Administrator grants naming the Crew Terms, {@code payment.share_held} notices, Team Issue
 * ending operations and a publication to the retired listener (AD-15, AD-20, AD-29, AD-40, SG-63): the tables and the
 * columns are gone, the share rows deleted, the grants stripped of the Crew Terms and ended, and a Rating issue stays a
 * Rating issue. No ledger row and no audit row is touched (AD-8). A database still holding a RELEASE_HELD stops at the
 * entry-type check, as the story accepts: the author resets it.
 */
class RetireCrewTermsMigrationIT {

	private static final PostgresTestDatabase database = PostgresTestDatabase.instance();

	private static final String BEFORE = "202611031120";

	private static final String AFTER = "202611031222";

	private static final String ACCOUNT = "00000000-0000-7000-8000-00000000e001";

	private static final String MENTOR = "00000000-0000-7000-8000-00000000e002";

	private static final String ADMIN = "00000000-0000-7000-8000-00000000e003";

	private static final String PRODUCT = "00000000-0000-7000-8000-00000000e101";

	private static final String JOB = "00000000-0000-7000-8000-00000000e102";

	private static final String MILESTONE = "00000000-0000-7000-8000-00000000e103";

	private static final String OPEN_SHARE = "00000000-0000-7000-8000-00000000e201";

	private static final String DECIDED_SHARE = "00000000-0000-7000-8000-00000000e202";

	private static final String RATING_ISSUE = "00000000-0000-7000-8000-00000000e203";

	private static final String TEAM_ISSUE_GRANT = "00000000-0000-7000-8000-00000000e301";

	private static final String ENDED_GRANT = "00000000-0000-7000-8000-00000000e302";

	private static final String DISPUTE_GRANT = "00000000-0000-7000-8000-00000000e303";

	private static final String TEAM_ISSUE_ITEMS = """
			[{"scope": "CREW_CHAT", "objectId": "", "accountId": ""},
			 {"scope": "WORK_ITEMS", "objectId": "", "accountId": "%s"},
			 {"scope": "CREW_TERMS", "objectId": "", "accountId": ""}]
			""".formatted(ACCOUNT);

	private static final String DISPUTE_ITEMS = """
			[{"scope": "JOB_CHAT", "objectId": "", "accountId": ""}, {"scope": "PAGES", "objectId": "", "accountId": ""}]
			""";

	@Test
	void theCrewTermsTheHoldsAndTheShareIssuesGoAndTheLedgerAndTheAuditTrailStay() throws Exception {
		String db = database.createScratchDatabase();
		flyway(db, BEFORE).migrate();
		execute(db, seed());
		List<String> ledger = rows(db, "SELECT id || ':' || entry_type || ':' || amount_vnd FROM payment.ledger_entry ORDER BY id");
		List<String> audit = rows(db, "SELECT id || ':' || action FROM audit.audit_entry ORDER BY id");

		flyway(db, AFTER).migrate();

		assertThat(rows(db, """
				SELECT coalesce(to_regclass('job.crew_terms_version')::text, 'gone') || ' '
				       || coalesce(to_regclass('job.crew_terms_share')::text, 'gone') || ' '
				       || coalesce(to_regclass('job.crew_terms_confirmation')::text, 'gone') || ' '
				       || coalesce(to_regclass('payment.share_hold')::text, 'gone') || ' '
				       || coalesce(to_regclass('payment.ledger_entry_reversal_idx')::text, 'gone') || ' '
				       || coalesce(to_regclass('dispute.team_issue_share_idx')::text, 'gone')
				""")).containsExactly("gone gone gone gone gone gone");
		assertThat(rows(db, """
				SELECT table_name || '.' || column_name FROM information_schema.columns
				WHERE (table_schema = 'job' AND table_name = 'milestone' AND column_name = 'crew_terms_version_no')
				   OR (table_schema = 'payment' AND table_name = 'ledger_entry' AND column_name IN ('hold_id', 'transfer_ids'))
				   OR (table_schema = 'dispute' AND table_name = 'team_issue'
				       AND column_name IN ('milestone_id', 'milestone_number', 'answer_points', 'outcome_points', 'decision_points'))
				""")).isEmpty();
		assertThat(rows(db, "SELECT id || ':' || subject || ':' || coalesce(answer_kind, '-') FROM dispute.team_issue"))
			.as("the Rating issue stays, the share ones go, decided or not")
			.containsExactly(RATING_ISSUE + ":RATING:KEEP");
		assertThat(rows(db, """
				SELECT is_nullable || ':' || coalesce(column_default, 'none') FROM information_schema.columns
				WHERE table_schema = 'dispute' AND table_name = 'team_issue' AND column_name IN ('rating_id', 'subject')
				ORDER BY column_name
				""")).containsExactly("NO:none", "NO:'RATING'::text");
		assertThat(rows(db, """
				SELECT conname || ' ' || pg_get_constraintdef(oid) FROM pg_constraint
				WHERE conrelid = 'dispute.team_issue'::regclass AND conname IN ('team_issue_subject_check', 'team_issue_answer_kind_check')
				ORDER BY conname
				""")).containsExactly("team_issue_answer_kind_check CHECK ((answer_kind = ANY (ARRAY['KEEP'::text, 'WITHDRAW_RATING'::text])))",
						"team_issue_subject_check CHECK ((subject = 'RATING'::text))");

		for (String schema : List.of("chat", "work", "job")) {
			assertThat(rows(db, """
					SELECT grant_id || ':' || (ends_at IS NOT NULL) || ':' || (SELECT string_agg(item ->> 'scope', ',' ORDER BY item ->> 'scope')
					    FROM jsonb_array_elements(items) AS item)
					FROM %s.admin_read_grant ORDER BY grant_id
					""".formatted(schema))).as(schema)
				.containsExactly(TEAM_ISSUE_GRANT + ":true:CREW_CHAT,WORK_ITEMS", ENDED_GRANT + ":true:CREW_CHAT",
						DISPUTE_GRANT + ":false:JOB_CHAT,PAGES");
			assertThat(rows(db, "SELECT ends_at::text FROM %s.admin_read_grant WHERE grant_id = '%s'".formatted(schema, ENDED_GRANT)))
				.as("an end already set stays").containsExactly("2026-10-01 03:00:00+00");
		}

		assertThat(rows(db, "SELECT type FROM notification.notification ORDER BY type"))
			.containsExactly("dispute.team_issue_raised");
		assertThat(rows(db, "SELECT operation_key FROM operations.external_operation ORDER BY operation_key"))
			.as("a finished ending is history, and another operation stays").containsExactly("e4", "e5");
		assertThat(rows(db, "SELECT listener_id FROM platform.event_publication ORDER BY listener_id"))
			.containsExactly("vn.vibefix.dispute.internal.TeamIssueListeners.onJobTransitioned(vn.vibefix.job.JobTransitioned)",
					"vn.vibefix.repo.internal.SetupListeners.onJobTransitioned(vn.vibefix.job.JobTransitioned)");
		assertThat(rows(db, "SELECT count(*)::text FROM platform.event_publication_stalled")).containsExactly("0");

		assertThat(rows(db, "SELECT id || ':' || entry_type || ':' || amount_vnd FROM payment.ledger_entry ORDER BY id"))
			.as("no ledger row is touched").isEqualTo(ledger);
		assertThat(rows(db, "SELECT id || ':' || action FROM audit.audit_entry ORDER BY id")).as("no audit row is touched")
			.isEqualTo(audit);
		assertThat(rows(db, "SELECT state || ':' || (funded_at IS NOT NULL) FROM job.milestone")).containsExactly("FUNDED:true");
		assertThatThrownBy(() -> execute(db, "UPDATE job.milestone SET funded_at = NULL"))
			.as("a funded Milestone still has its funding time").hasMessageContaining("milestone_funded_check");
		assertThatThrownBy(() -> execute(db, """
				INSERT INTO payment.ledger_entry (id, operation_key, entry_type, job_id, product_id, milestone_id, amount_vnd,
				    party_account_id, created_at)
				VALUES (gen_random_uuid(), 'k-held', 'RELEASE_HELD', '%s', '%s', '%s', 1000, '%s', now())
				""".formatted(JOB, PRODUCT, MILESTONE, ACCOUNT))).hasMessageContaining("ledger_entry_entry_type_check");
	}

	@Test
	void aDatabaseStillHoldingAReleaseHeldStopsAtTheEntryTypeCheck() throws Exception {
		String db = database.createScratchDatabase();
		flyway(db, BEFORE).migrate();
		execute(db, job());
		execute(db, """
				INSERT INTO payment.ledger_entry (id, operation_key, entry_type, job_id, product_id, milestone_id, amount_vnd,
				    party_account_id, created_at)
				VALUES (gen_random_uuid(), 'k-held', 'RELEASE_HELD', '%s', '%s', '%s', 1000, '%s', now())
				""".formatted(JOB, PRODUCT, MILESTONE, ACCOUNT));

		assertThatThrownBy(() -> flyway(db, AFTER).migrate()).isInstanceOf(FlywayException.class)
			.hasMessageContaining("ledger_entry_entry_type_check");
	}

	/** The Job with its pinned Milestone, its Crew Terms and its ledger account. */
	private static String[] job() {
		return new String[] { """
				INSERT INTO job.job (id, product_id, customer_account_id, state, title, created_at, updated_at)
				VALUES ('%s', '%s', '%s', 'DRAFT', 'Sửa lỗi đặt bàn', now(), now())
				""".formatted(JOB, PRODUCT, ACCOUNT), """
				INSERT INTO job.crew_terms_version (job_id, version_no, status, cap_percent, author_account_id, created_at)
				VALUES ('%s', 1, 'IN_FORCE', 100, '%s', now())
				""".formatted(JOB, MENTOR), """
				INSERT INTO job.crew_terms_share (job_id, version_no, party_account_id, crew_row_id, percent)
				VALUES ('%s', 1, '%s', NULL, 100)
				""".formatted(JOB, MENTOR), """
				INSERT INTO job.milestone (id, job_id, position, title, revision_rounds, created_at, state, funded_at,
				    crew_terms_version_no)
				VALUES ('%s', '%s', 0, 'Giai đoạn 1', 2, now(), 'FUNDED', now(), 1)
				""".formatted(MILESTONE, JOB), """
				INSERT INTO payment.ledger_account (milestone_id, job_id, product_id, created_at) VALUES ('%s', '%s', '%s', now())
				""".formatted(MILESTONE, JOB, PRODUCT) };
	}

	private static String[] seed() {
		List<String> statements = new ArrayList<>(List.of(job()));
		statements.addAll(List.of("""
				INSERT INTO payment.ledger_entry (id, operation_key, entry_type, bucket, job_id, product_id, milestone_id, amount_vnd,
				    vnp_transaction_no, created_at)
				VALUES ('00000000-0000-7000-8000-00000000e401', 'k-fund', 'FUND', 'MILESTONE', '%1$s', '%2$s', '%3$s', 1000000,
				        '91000001', now()),
				       ('00000000-0000-7000-8000-00000000e402', 'k-pool', 'RELEASE_POOLED', NULL, '%1$s', '%2$s', '%3$s', 1000000,
				        NULL, now())
				""".formatted(JOB, PRODUCT, MILESTONE), """
				INSERT INTO dispute.team_issue (id, job_id, product_id, subject, milestone_id, milestone_number, raiser_account_id,
				    mentor_account_id, reason, phase, answer_days, escalation_days, raised_at, answer_due_at)
				VALUES ('%s', '%s', '%s', 'SHARE', '%s', 1, '%s', '%s', 'Phần chia chưa đúng', 'ANSWER', 5, 5, now(), now())
				""".formatted(OPEN_SHARE, JOB, PRODUCT, MILESTONE, ACCOUNT, MENTOR), """
				INSERT INTO dispute.team_issue (id, job_id, product_id, subject, milestone_id, milestone_number, raiser_account_id,
				    mentor_account_id, reason, phase, answer_days, escalation_days, raised_at, answer_due_at, escalation_due_at,
				    ended_at, answer_kind, answer_points, answered_at, escalated_at, ending, outcome_points, assigned_admin_id,
				    assigned_at, grant_id, decided_by_admin_id, decision_points, decision_reasoning, decided_at)
				VALUES ('%s', '%s', '%s', 'SHARE', '%s', 1, gen_random_uuid(), '%s', 'Phần chia chưa đúng', 'ENDED', 5, 5,
				    now(), now(), now(), now(), 'TRANSFER', 10, now(), now(), 'DECIDED', 10, '%s', now(), '%s', '%s', 10,
				    'Chuyển 10 điểm', now())
				""".formatted(DECIDED_SHARE, JOB, PRODUCT, MILESTONE, MENTOR, ADMIN, TEAM_ISSUE_GRANT, ADMIN), """
				INSERT INTO dispute.team_issue (id, job_id, product_id, subject, rating_id, raiser_account_id, mentor_account_id,
				    reason, phase, answer_days, escalation_days, raised_at, answer_due_at, escalation_due_at, answer_kind,
				    answered_at)
				VALUES ('%s', '%s', '%s', 'RATING', gen_random_uuid(), gen_random_uuid(), '%s', 'Điểm chưa đúng', 'ESCALATION', 5,
				    5, now(), now(), now(), 'KEEP', now())
				""".formatted(RATING_ISSUE, JOB, PRODUCT, MENTOR), """
				INSERT INTO payment.share_hold (team_issue_id, milestone_id, job_id, product_id, raiser_account_id, held_at)
				VALUES ('%s', '%s', '%s', '%s', '%s', now())
				""".formatted(OPEN_SHARE, MILESTONE, JOB, PRODUCT, ACCOUNT)));
		for (String schema : List.of("chat", "work", "job")) {
			statements.add("""
					INSERT INTO %1$s.admin_read_grant (grant_id, admin_account_id, job_id, items, reason_id, ends_at)
					VALUES ('%2$s', '%3$s', '%4$s', '%5$s', '%6$s', NULL),
					       ('%7$s', '%3$s', '%4$s', '[{"scope": "CREW_CHAT", "objectId": "", "accountId": ""},
					            {"scope": "CREW_TERMS", "objectId": "", "accountId": ""}]', '%8$s', '2026-10-01 03:00:00+00'),
					       ('%9$s', '%3$s', '%4$s', '%10$s', gen_random_uuid(), NULL)
					""".formatted(schema, TEAM_ISSUE_GRANT, ADMIN, JOB, TEAM_ISSUE_ITEMS.strip(), DECIDED_SHARE, ENDED_GRANT,
					RATING_ISSUE, DISPUTE_GRANT, DISPUTE_ITEMS.strip()));
		}
		statements.addAll(List.of("""
				INSERT INTO notification.notification (id, account_id, type, causing_record_id, params, link, created_at, read_at)
				VALUES (gen_random_uuid(), '%1$s', 'payment.share_held', gen_random_uuid(), '{"n": "1"}', '/so-du', now(), NULL),
				       (gen_random_uuid(), '%1$s', 'payment.share_held', gen_random_uuid(), '{"n": "1"}', '/so-du', now(), now()),
				       (gen_random_uuid(), '%2$s', 'dispute.team_issue_raised', gen_random_uuid(), '{}', '/cong-viec', now(), NULL)
				""".formatted(ACCOUNT, MENTOR), """
				INSERT INTO operations.external_operation (id, operation_key, type, lane, priority, status, payload,
				    next_attempt_at, created_at, completed_at, lease_until)
				VALUES (gen_random_uuid(), 'e1', 'dispute.team_issue_ending', 'VNPAY', 0, 'PENDING', '{}', now(), now(), NULL, NULL),
				       (gen_random_uuid(), 'e2', 'dispute.team_issue_ending', 'VNPAY', 0, 'RUNNING', '{}', now(), now(), NULL,
				        now() + INTERVAL '1 minute'),
				       (gen_random_uuid(), 'e3', 'dispute.team_issue_ending', 'VNPAY', 0, 'FAILED', '{}', now(), now(), now(), NULL),
				       (gen_random_uuid(), 'e4', 'dispute.team_issue_ending', 'VNPAY', 0, 'SUCCEEDED', '{}', now(), now(), now(),
				        NULL),
				       (gen_random_uuid(), 'e5', 'dispute.team_issue_overdue', 'VNPAY', 0, 'PENDING', '{}', now(), now(), NULL, NULL)
				""", """
				INSERT INTO platform.event_publication (id, listener_id, event_type, serialized_event, publication_date,
				    completion_date)
				VALUES ('00000000-0000-7000-8000-00000000e501',
				        'vn.vibefix.dispute.internal.TeamIssueListeners.onJobTransitioned(vn.vibefix.job.JobTransitioned)',
				        'vn.vibefix.job.JobTransitioned', '{}', now(), NULL),
				       ('00000000-0000-7000-8000-00000000e502',
				        'vn.vibefix.dispute.internal.TeamIssueListeners.onJobTransitioned(vn.vibefix.job.JobTransitioned)',
				        'vn.vibefix.job.JobTransitioned', '{}', now(), now()),
				       ('00000000-0000-7000-8000-00000000e503',
				        'vn.vibefix.repo.internal.SetupListeners.onJobTransitioned(vn.vibefix.job.JobTransitioned)',
				        'vn.vibefix.job.JobTransitioned', '{}', now(), NULL)
				""", """
				INSERT INTO platform.event_publication_stalled (publication_id, stalled_at)
				VALUES ('00000000-0000-7000-8000-00000000e501', now())
				""", """
				INSERT INTO audit.audit_entry (id, occurred_at, actor_type, actor_account_id, action, job_id)
				VALUES ('00000000-0000-7000-8000-00000000e601', now(), 'USER', '%1$s', 'job.admin_read', '%2$s'),
				       ('00000000-0000-7000-8000-00000000e602', now(), 'USER', '%1$s', 'dispute.team_issue_decided', '%2$s')
				""".formatted(ADMIN, JOB), """
				INSERT INTO audit.audit_entry_object (entry_id, position, object_type, object_id)
				VALUES ('00000000-0000-7000-8000-00000000e601', 0, 'job.crew_terms_version', '%s'),
				       ('00000000-0000-7000-8000-00000000e602', 0, 'dispute.team_issue', '%s')
				""".formatted(JOB, DECIDED_SHARE)));
		return statements.toArray(String[]::new);
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
