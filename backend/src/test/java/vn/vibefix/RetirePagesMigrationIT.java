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
 * Story 24.1's five forward migrations (D2, FR-10, FR-11, FR-29, FR-48, FR-70, AD-8, AD-20) on a database that
 * still holds the Documentation Space: two Pages with their versions, a share of one Job's Pages with another, a
 * handover pinning the Report Page at a version, the Pages that handover listed, two follow-ups citing numbered
 * recommendations, the {@code work.pages_shared} notices, and Dispute grants naming {@code PAGES} in all three
 * schemas.
 * <p>
 * Afterwards the three {@code work.page*} tables and {@code delivery.handover_page} are gone with their indexes,
 * {@code delivery.handover} has neither report column nor the CHECK that tied them together, every follow-up row
 * reads its recommendation as the backfilled {@code Đề xuất số {n}} within the new 500-character CHECK, and each
 * grant keeps its other four items. What stays: the Board itself (Story 24.2 owns it), the audit entries of the
 * shares (AD-8), a notification of another type, and the follow-up rows themselves with everything else they said.
 */
class RetirePagesMigrationIT {

	private static final PostgresTestDatabase database = PostgresTestDatabase.instance();

	/** The version right before this story's first migration. */
	private static final String BEFORE = "202611190900";

	/** The last of its five. */
	private static final String AFTER = "202611191004";

	private static final String PRODUCT = "00000000-0000-7000-8000-000000240101";

	private static final String JOB = "00000000-0000-7000-8000-000000240102";

	private static final String NEW_JOB = "00000000-0000-7000-8000-000000240103";

	private static final String MENTOR = "00000000-0000-7000-8000-000000240104";

	private static final String ADMIN = "00000000-0000-7000-8000-000000240105";

	private static final String CUSTOMER = "00000000-0000-7000-8000-000000240106";

	private static final String REPORT_PAGE = "00000000-0000-7000-8000-000000240201";

	private static final String TEAM_PAGE = "00000000-0000-7000-8000-000000240202";

	private static final String HANDOVER = "00000000-0000-7000-8000-000000240301";

	private static final String FIRST_FOLLOW_UP = "00000000-0000-7000-8000-000000240401";

	private static final String SECOND_FOLLOW_UP = "00000000-0000-7000-8000-000000240402";

	private static final String GRANT = "00000000-0000-7000-8000-000000240501";

	private static final String DISPUTE_ITEMS = """
			[{"scope": "JOB_CHAT", "objectId": "", "accountId": ""},
			 {"scope": "WORK_ITEMS", "objectId": "", "accountId": ""},
			 {"scope": "WORK_ITEM_NOTES", "objectId": "", "accountId": ""},
			 {"scope": "ATTACHMENTS", "objectId": "", "accountId": ""},
			 {"scope": "PAGES", "objectId": "", "accountId": ""}]
			""";

	@Test
	void thePagesTheirSharesAndTheReportReferencesGoAndEveryFollowUpKeepsItsRecommendation() throws Exception {
		String db = database.createScratchDatabase();
		flyway(db, BEFORE).migrate();
		execute(db, seed());
		List<String> audit = rows(db, "SELECT id || ':' || action FROM audit.audit_entry ORDER BY id");

		flyway(db, AFTER).migrate();

		assertThat(rows(db, """
				SELECT coalesce(to_regclass('work.page')::text, 'gone') || ' '
				       || coalesce(to_regclass('work.page_version')::text, 'gone') || ' '
				       || coalesce(to_regclass('work.page_share')::text, 'gone') || ' '
				       || coalesce(to_regclass('work.page_job_idx')::text, 'gone') || ' '
				       || coalesce(to_regclass('work.page_report_idx')::text, 'gone') || ' '
				       || coalesce(to_regclass('delivery.handover_page')::text, 'gone') || ' '
				       || coalesce(to_regclass('work.board')::text, 'gone')
				""")).containsExactly("gone gone gone gone gone gone work.board");
		assertThat(rows(db, """
				SELECT table_name || '.' || column_name FROM information_schema.columns
				WHERE table_schema = 'delivery' AND table_name IN ('handover', 'follow_up')
				  AND column_name IN ('report_page_id', 'report_version_no', 'recommendation_no')
				""")).isEmpty();
		assertThat(rows(db, """
				SELECT count(*)::text FROM pg_constraint WHERE conrelid = 'delivery.handover'::regclass
				  AND pg_get_constraintdef(oid) LIKE '%report_page_id%'
				""")).containsExactly("0");

		assertThat(rows(db, """
				SELECT id || ':' || recommendation_text || ':' || title FROM delivery.follow_up ORDER BY created_at, id
				""")).as("every row keeps its citation, backfilled from the number it held")
			.containsExactly(FIRST_FOLLOW_UP + ":Đề xuất số 1:Sửa lỗi đặt trùng bàn",
					SECOND_FOLLOW_UP + ":Đề xuất số 12:Viết hướng dẫn");
		assertThat(rows(db, """
				SELECT is_nullable FROM information_schema.columns WHERE table_schema = 'delivery'
				  AND table_name = 'follow_up' AND column_name = 'recommendation_text'
				""")).containsExactly("NO");
		assertThat(rows(db, """
				SELECT pg_get_constraintdef(oid) FROM pg_constraint WHERE conrelid = 'delivery.follow_up'::regclass
				  AND conname = 'follow_up_recommendation_text_check'
				""")).singleElement().asString().contains("char_length(recommendation_text)", "500");
		assertThat(rows(db, """
				SELECT indexdef FROM pg_indexes WHERE schemaname = 'delivery' AND indexname = 'follow_up_job_idx'
				""")).as("the index the dropped column took with it is back, without it")
			.containsExactly("CREATE INDEX follow_up_job_idx ON delivery.follow_up USING btree (job_id, recommendation_text, created_at)");

		assertThat(rows(db, "SELECT type FROM notification.notification ORDER BY type"))
			.as("the shares' notices go, read or not; another type stays")
			.containsExactly("work.work_item_assigned");
		for (String schema : List.of("chat", "work", "job")) {
			assertThat(rows(db, """
					SELECT (SELECT string_agg(item ->> 'scope', ',' ORDER BY item ->> 'scope')
					    FROM jsonb_array_elements(items) AS item) FROM %s.admin_read_grant
					""".formatted(schema))).as(schema)
				.containsExactly("ATTACHMENTS,JOB_CHAT,WORK_ITEM_NOTES,WORK_ITEMS");
			assertThat(rows(db, "SELECT (ends_at IS NULL)::text FROM %s.admin_read_grant".formatted(schema)))
				.as("%s: a grant that still serves its case keeps its end open", schema)
				.containsExactly("true");
		}
		assertThat(rows(db, "SELECT id || ':' || action FROM audit.audit_entry ORDER BY id"))
			.as("no audit row is touched")
			.isEqualTo(audit);
	}

	private static String[] seed() {
		List<String> statements = new ArrayList<>(List.of("""
				INSERT INTO work.board (job_id, product_id, code, next_number, created_at)
				VALUES ('%1$s', '%2$s', 'SDB', 1, now()), ('%3$s', '%2$s', 'SDC', 1, now())
				""".formatted(JOB, PRODUCT, NEW_JOB), """
				INSERT INTO work.page (id, job_id, title, version_no, created_by_account_id, created_at, updated_at,
				    template, visibility)
				VALUES ('%1$s', '%3$s', 'Báo cáo đánh giá', 2, '%4$s', now(), now(), 'ASSESSMENT_REPORT', 'CUSTOMER'),
				       ('%2$s', '%3$s', 'Ghi chú kỹ thuật', 1, '%4$s', now(), now(), NULL, 'TEAM')
				""".formatted(REPORT_PAGE, TEAM_PAGE, JOB, MENTOR), """
				INSERT INTO work.page_version (page_id, version_no, title, body, author_account_id, created_at)
				VALUES ('%1$s', 1, 'Báo cáo đánh giá', '## Đề xuất', '%3$s', now()),
				       ('%1$s', 2, 'Báo cáo đánh giá', '## Đề xuất\\n\\n1. Sửa lỗi.', '%3$s', now()),
				       ('%2$s', 1, 'Ghi chú kỹ thuật', '# Cách chạy', '%3$s', now())
				""".formatted(REPORT_PAGE, TEAM_PAGE, MENTOR), """
				INSERT INTO work.page_share (job_id, source_job_id, created_at) VALUES ('%s', '%s', now())
				""".formatted(NEW_JOB, JOB), """
				INSERT INTO delivery.handover (id, job_id, product_id, milestone_id, milestone_number, milestone_title,
				    round, mentor_account_id, status, job_version, transition_id, report_page_id, report_version_no,
				    check_by, created_at, decided_at, decided_by)
				VALUES ('%1$s', '%2$s', '%3$s', gen_random_uuid(), 1, 'Báo cáo đánh giá', 1, '%4$s', 'ACCEPTED', 3,
				        gen_random_uuid(), '%5$s', 2, now(), now(), now(), '%6$s')
				""".formatted(HANDOVER, JOB, PRODUCT, MENTOR, REPORT_PAGE, CUSTOMER), """
				INSERT INTO delivery.handover_page (handover_id, page_id, title, version_no, customer_visible)
				VALUES ('%1$s', '%2$s', 'Báo cáo đánh giá', 2, TRUE), ('%1$s', '%3$s', 'Ghi chú kỹ thuật', 1, FALSE)
				""".formatted(HANDOVER, REPORT_PAGE, TEAM_PAGE), """
				INSERT INTO delivery.follow_up (id, job_id, product_id, handover_id, report_page_id, report_version_no,
				    recommendation_no, title, job_types, skill_ids, scope, criteria, effort_days, status,
				    mentor_account_id, created_at, updated_at)
				VALUES ('%1$s', '%3$s', '%4$s', '%5$s', '%6$s', 2, 1, 'Sửa lỗi đặt trùng bàn', ARRAY['BUG_FIX'], '{}',
				        'Sửa lỗi hai khách đặt trùng bàn.', ARRAY['Hai khách không đặt trùng bàn'], 5, 'DRAFTING',
				        '%7$s', now() - INTERVAL '1 hour', now()),
				       ('%2$s', '%3$s', '%4$s', '%5$s', '%6$s', 2, 12, 'Viết hướng dẫn', ARRAY['DOCUMENTATION'], '{}',
				        'Viết hướng dẫn cho nhân viên.', ARRAY['Có hướng dẫn'], 3, 'DRAFTING', '%7$s', now(), now())
				""".formatted(FIRST_FOLLOW_UP, SECOND_FOLLOW_UP, JOB, PRODUCT, HANDOVER, REPORT_PAGE, MENTOR), """
				INSERT INTO notification.notification (id, account_id, type, causing_record_id, params, link, job_id,
				    created_at, read_at)
				VALUES (gen_random_uuid(), '%1$s', 'work.pages_shared', gen_random_uuid(), '{}',
				        '/cong-viec/%2$s/tai-lieu-cu', '%2$s', now(), NULL),
				       (gen_random_uuid(), '%1$s', 'work.pages_shared', gen_random_uuid(), '{}',
				        '/cong-viec/%2$s/tai-lieu-cu', '%2$s', now(), now()),
				       (gen_random_uuid(), '%1$s', 'work.work_item_assigned', gen_random_uuid(), '{}',
				        '/cong-viec/%2$s/bang', '%2$s', now(), NULL)
				""".formatted(MENTOR, NEW_JOB), """
				INSERT INTO audit.audit_entry (id, occurred_at, actor_type, actor_account_id, action, job_id)
				VALUES ('00000000-0000-7000-8000-000000240601', now(), 'USER', '%1$s', 'work.pages_shared', '%2$s'),
				       ('00000000-0000-7000-8000-000000240602', now(), 'USER', '%1$s', 'work.pages_unshared', '%2$s')
				""".formatted(MENTOR, NEW_JOB)));
		for (String schema : List.of("chat", "work", "job")) {
			statements.add("""
					INSERT INTO %1$s.admin_read_grant (grant_id, admin_account_id, job_id, items, reason_id, ends_at)
					VALUES ('%2$s', '%3$s', '%4$s', '%5$s', gen_random_uuid(), NULL)
					""".formatted(schema, GRANT, ADMIN, JOB, DISPUTE_ITEMS.strip()));
		}
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
