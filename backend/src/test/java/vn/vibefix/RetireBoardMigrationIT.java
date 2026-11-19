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
 * Story 24.2b's eight forward migrations (D1, FR-22 to FR-26, FR-32, FR-48, FR-50, FR-62, FR-69, FR-76, NFR-3,
 * AD-2, AD-3, AD-8, AD-20, AD-23, AD-33) on a database that still holds the Board: a Job's Board with two Work
 * Items, their history, a note, an Attachment with its stored file, a commit link, a deferred Product deletion, a
 * handover with the note it carried and a commit naming a Work Item key, the four Work Item notices and the three
 * Work Item to-dos, an unfinished publication to a {@code work} listener, Dispute grants naming the three retired
 * scopes in all three schemas, and two Portfolio Entries frozen before the story.
 * <p>
 * Afterwards the schema {@code work} is gone with its seven tables, {@code delivery.handover_item_note} and
 * {@code delivery.handover_commit.work_item_keys} are gone while the commit keeps its SHA, message and author, each
 * surviving grant reads back with its remaining items, the retired notices and to-dos are gone while another type
 * stays, the Attachment's stored file is marked deleted while the Crew Chat's is untouched, the publication to the
 * deleted listener is gone while another stays, and {@code reputation.portfolio_entry} carries
 * {@code commits_authored} in place of the two Board facts, NULL on the rows frozen before. What stays: the audit
 * entries of every Work Item action (AD-8), and the Job, its Milestones, its handover and its money.
 */
class RetireBoardMigrationIT {

	private static final PostgresTestDatabase database = PostgresTestDatabase.instance();

	/** The version right before this story's first migration: Story 24.1's last. */
	private static final String BEFORE = "202611191004";

	/** The last of its eight. */
	private static final String AFTER = "202611191107";

	private static final String PRODUCT = "00000000-0000-7000-8000-000000240701";

	private static final String JOB = "00000000-0000-7000-8000-000000240702";

	private static final String MENTOR = "00000000-0000-7000-8000-000000240703";

	private static final String ADMIN = "00000000-0000-7000-8000-000000240704";

	private static final String CUSTOMER = "00000000-0000-7000-8000-000000240705";

	private static final String CREW = "00000000-0000-7000-8000-000000240706";

	private static final String MILESTONE = "00000000-0000-7000-8000-000000240801";

	private static final String STORY = "00000000-0000-7000-8000-000000240802";

	private static final String TASK = "00000000-0000-7000-8000-000000240803";

	private static final String NOTE = "00000000-0000-7000-8000-000000240804";

	private static final String ATTACHMENT = "00000000-0000-7000-8000-000000240805";

	private static final String ATTACHMENT_FILE = "00000000-0000-7000-8000-000000240806";

	private static final String CHAT_FILE = "00000000-0000-7000-8000-000000240807";

	private static final String HANDOVER = "00000000-0000-7000-8000-000000240901";

	private static final String GRANT = "00000000-0000-7000-8000-000000241001";

	private static final String DISPUTE_ITEMS = """
			[{"scope": "JOB_CHAT", "objectId": "", "accountId": ""},
			 {"scope": "WORK_ITEMS", "objectId": "", "accountId": ""},
			 {"scope": "WORK_ITEM_NOTES", "objectId": "", "accountId": ""},
			 {"scope": "ATTACHMENTS", "objectId": "", "accountId": ""}]
			""";

	/** A grant that held nothing but retired scopes keeps an empty list, never the NULL jsonb_agg would give. */
	private static final String RETIRED_ONLY = """
			[{"scope": "WORK_ITEMS", "objectId": "", "accountId": "%s"}]
			""".formatted(CREW);

	@Test
	void theBoardItsSchemaItsNoticesAndItsPortfolioFactsGoAndEveryGrantReadsBack() throws Exception {
		String db = database.createScratchDatabase();
		flyway(db, BEFORE).migrate();
		execute(db, seed());
		List<String> audit = rows(db, "SELECT id || ':' || action FROM audit.audit_entry ORDER BY id");

		flyway(db, AFTER).migrate();

		assertThat(rows(db, "SELECT count(*)::text FROM information_schema.schemata WHERE schema_name = 'work'"))
			.as("the schema is dropped with its seven tables")
			.containsExactly("0");
		assertThat(rows(db, """
				SELECT coalesce(to_regclass('delivery.handover_item_note')::text, 'gone') || ' '
				       || coalesce(to_regclass('delivery.handover_commit')::text, 'gone')
				""")).containsExactly("gone delivery.handover_commit");
		assertThat(rows(db, """
				SELECT column_name FROM information_schema.columns
				WHERE table_schema = 'delivery' AND table_name = 'handover_commit' AND column_name = 'work_item_keys'
				""")).isEmpty();
		assertThat(rows(db, "SELECT sha || ':' || message || ':' || author_github_id FROM delivery.handover_commit"))
			.as("the commit keeps everything but the Work Item link")
			.containsExactly("a".repeat(40) + ":Sửa lỗi đặt trùng bàn (SDB-1):4242");

		for (String schema : List.of("chat", "job")) {
			assertThat(rows(db, """
					SELECT grant_id::text || ':' || coalesce((SELECT string_agg(item ->> 'scope', ',' ORDER BY item ->> 'scope')
					    FROM jsonb_array_elements(items) AS item), '-') FROM %s.admin_read_grant ORDER BY grant_id
					""".formatted(schema))).as(schema)
				.containsExactly(GRANT + ":JOB_CHAT", "00000000-0000-7000-8000-000000241002:-");
			assertThat(rows(db, "SELECT (ends_at IS NULL)::text FROM %s.admin_read_grant WHERE grant_id = '%s'"
					.formatted(schema, GRANT)))
				.as("%s: a grant that still serves its case keeps its end open", schema)
				.containsExactly("true");
		}

		assertThat(rows(db, "SELECT type FROM notification.notification ORDER BY type"))
			.as("the four Work Item notices go, read or not; another type stays")
			.containsExactly("job.milestone_submitted");
		assertThat(rows(db, "SELECT todo_key FROM notification.todo ORDER BY todo_key"))
			.as("the three Work Item to-dos go, open or closed; another key stays")
			.containsExactly("delivery.review_handover");

		assertThat(rows(db, "SELECT id::text || ':' || (deleted_at IS NOT NULL)::text FROM files.stored_file ORDER BY id"))
			.as("the Attachment's bytes go with its row; the Crew Chat's file is untouched")
			.containsExactly(ATTACHMENT_FILE + ":true", CHAT_FILE + ":false");

		assertThat(rows(db, "SELECT listener_id FROM platform.event_publication ORDER BY listener_id"))
			.as("the unfinished publication to the deleted listener goes; the completed one and another listener stay")
			.containsExactly("vn.vibefix.chat.internal.ChatListeners.onJobTransitioned(vn.vibefix.job.JobTransitioned)",
					"vn.vibefix.work.internal.WorkListeners.onAgreementConfirmed(vn.vibefix.job.AgreementConfirmed)");
		assertThat(rows(db, "SELECT count(*)::text FROM platform.event_publication_stalled")).containsExactly("0");

		assertThat(rows(db, """
				SELECT column_name FROM information_schema.columns WHERE table_schema = 'reputation'
				  AND table_name = 'portfolio_entry' AND column_name IN ('work_items_approved', 'reviews_written')
				""")).isEmpty();
		assertThat(rows(db, """
				SELECT role || ':' || coalesce(commits_authored::text, '-') || ':' || coalesce(milestones_handed_over::text, '-')
				FROM reputation.portfolio_entry ORDER BY role
				""")).as("a Crew entry frozen before this story counts no commit, and the Mentor's keeps its Milestones")
			.containsExactly("CREW:-:-", "MENTOR:-:1");

		assertThat(rows(db, "SELECT id || ':' || action FROM audit.audit_entry ORDER BY id"))
			.as("no audit row is touched")
			.isEqualTo(audit);
		assertThat(rows(db, "SELECT count(*)::text FROM delivery.handover")).as("the handover itself stays")
			.containsExactly("1");
	}

	private static String[] seed() {
		List<String> statements = new ArrayList<>(List.of("""
				INSERT INTO work.board (job_id, product_id, code, next_number, created_at)
				VALUES ('%1$s', '%2$s', 'SDB', 3, now())
				""".formatted(JOB, PRODUCT), """
				INSERT INTO work.work_item (id, job_id, number, type, title, description, milestone_id, status,
				    assignee_account_id, created_at, updated_at)
				VALUES ('%1$s', '%3$s', 1, 'STORY', 'Hai khách không đặt trùng bàn', '1. Mở.', '%4$s', 'DONE', '%5$s',
				        now(), now()),
				       ('%2$s', '%3$s', 2, 'TASK', 'Phụ lục thuật ngữ', 'Viết.', '%4$s', 'IN_PROGRESS', '%5$s', now(), now())
				""".formatted(STORY, TASK, JOB, MILESTONE, CREW), """
				INSERT INTO files.stored_file (id, purpose, account_id, file_name, media_type, kind, size_bytes, sha256,
				    stored_at)
				VALUES ('%1$s', 'work.attachment', '%3$s', 'bao-cao.pdf', 'application/pdf', 'PDF', 2048, repeat('a', 64),
				        now()),
				       ('%2$s', 'chat.attachment', '%3$s', 'bien-ban.pdf', 'application/pdf', 'PDF', 1024, repeat('b', 64),
				        now())
				""".formatted(ATTACHMENT_FILE, CHAT_FILE, MENTOR), """
				INSERT INTO work.attachment (id, work_item_id, job_id, kind, file_id, file_name, media_type, size_bytes,
				    author_account_id, added_at)
				VALUES ('%1$s', '%2$s', '%3$s', 'FILE', '%4$s', 'bao-cao.pdf', 'application/pdf', 2048, '%5$s', now())
				""".formatted(ATTACHMENT, STORY, JOB, ATTACHMENT_FILE, MENTOR), """
				INSERT INTO work.work_item_history (id, work_item_id, job_id, actor_account_id, action, changes, comment,
				    attachment_id, at)
				VALUES ('%1$s', '%2$s', '%3$s', '%4$s', 'NOTE', '{}'::jsonb, 'Đã rà soát phần đặt bàn', NULL, now()),
				       (gen_random_uuid(), '%2$s', '%3$s', '%5$s', 'ATTACHED', '{}'::jsonb, NULL, '%6$s', now())
				""".formatted(NOTE, STORY, JOB, CREW, MENTOR, ATTACHMENT), """
				INSERT INTO work.split_deferral (job_id, product_id, waiting, deferred_at)
				VALUES ('%s', '%s', 'DELETE', now())
				""".formatted(JOB, PRODUCT), """
				INSERT INTO delivery.handover (id, job_id, product_id, milestone_id, milestone_number, milestone_title,
				    round, mentor_account_id, status, job_version, transition_id, check_by, created_at, decided_at, decided_by)
				VALUES ('%1$s', '%2$s', '%3$s', '%4$s', 1, 'Chặn đặt trùng bàn', 1, '%5$s', 'ACCEPTED', 3,
				        gen_random_uuid(), now(), now(), now(), '%6$s')
				""".formatted(HANDOVER, JOB, PRODUCT, MILESTONE, MENTOR, CUSTOMER), """
				INSERT INTO delivery.handover_item_note (handover_id, work_item_id, item_key, title, status, note)
				VALUES ('%s', '%s', 'SDB-2', 'Phụ lục thuật ngữ', 'IN_PROGRESS', 'Làm ở giai đoạn sau.')
				""".formatted(HANDOVER, TASK), """
				INSERT INTO delivery.handover_commit (handover_id, position, sha, message, author_github_id, work_item_keys)
				VALUES ('%s', 0, repeat('a', 40), 'Sửa lỗi đặt trùng bàn (SDB-1)', 4242, ARRAY['SDB-1'])
				""".formatted(HANDOVER), """
				INSERT INTO work.work_item_commit (work_item_id, sha, job_id, handover_id, linked_at)
				VALUES ('%s', repeat('a', 40), '%s', '%s', now())
				""".formatted(STORY, JOB, HANDOVER), """
				INSERT INTO notification.notification (id, account_id, type, causing_record_id, params, link, job_id,
				    created_at, read_at)
				VALUES (gen_random_uuid(), '%1$s', 'work.work_item_assigned', gen_random_uuid(), '{}', '/cong-viec/%2$s/bang',
				        '%2$s', now(), NULL),
				       (gen_random_uuid(), '%1$s', 'work.review_requested', gen_random_uuid(), '{}', '/cong-viec/%2$s/bang',
				        '%2$s', now(), now()),
				       (gen_random_uuid(), '%1$s', 'work.work_item_approved', gen_random_uuid(), '{}', '/cong-viec/%2$s/bang',
				        '%2$s', now(), NULL),
				       (gen_random_uuid(), '%1$s', 'work.work_item_returned', gen_random_uuid(), '{}', '/cong-viec/%2$s/bang',
				        '%2$s', now(), NULL),
				       (gen_random_uuid(), '%1$s', 'job.milestone_submitted', gen_random_uuid(), '{}', '/cong-viec/%2$s',
				        '%2$s', now(), NULL)
				""".formatted(CREW, JOB), """
				INSERT INTO notification.todo (id, todo_key, account_id, subject_id, tier, params, link, job_id, opened_at,
				    closed_at, role)
				VALUES (gen_random_uuid(), 'work.review_work_item', '%1$s', gen_random_uuid(), 2, '{}'::jsonb,
				        '/cong-viec/%3$s/bang', '%3$s', now(), NULL, 'MENTOR'),
				       (gen_random_uuid(), 'work.fix_returned_item', '%2$s', gen_random_uuid(), 2, '{}'::jsonb,
				        '/cong-viec/%3$s/bang', '%3$s', now(), now(), 'CREW'),
				       (gen_random_uuid(), 'work.reassign_work_items', '%1$s', gen_random_uuid(), 2, '{}'::jsonb,
				        '/cong-viec/%3$s/bang', '%3$s', now(), NULL, 'MENTOR'),
				       (gen_random_uuid(), 'delivery.review_handover', '%4$s', gen_random_uuid(), 1, '{}'::jsonb,
				        '/cong-viec/%3$s', '%3$s', now(), NULL, 'CUSTOMER')
				""".formatted(MENTOR, CREW, JOB, CUSTOMER), """
				INSERT INTO platform.event_publication (id, listener_id, event_type, serialized_event, publication_date,
				    completion_date)
				VALUES (gen_random_uuid(),
				        'vn.vibefix.work.internal.WorkListeners.onJobTransitioned(vn.vibefix.job.JobTransitioned)',
				        'vn.vibefix.job.JobTransitioned', '{}', now(), NULL),
				       (gen_random_uuid(),
				        'vn.vibefix.work.internal.WorkListeners.onAgreementConfirmed(vn.vibefix.job.AgreementConfirmed)',
				        'vn.vibefix.job.AgreementConfirmed', '{}', now(), now()),
				       (gen_random_uuid(),
				        'vn.vibefix.chat.internal.ChatListeners.onJobTransitioned(vn.vibefix.job.JobTransitioned)',
				        'vn.vibefix.job.JobTransitioned', '{}', now(), NULL)
				""", """
				INSERT INTO platform.event_publication_stalled (publication_id, stalled_at)
				SELECT id, now() FROM platform.event_publication
				WHERE completion_date IS NULL AND listener_id LIKE 'vn.vibefix.work.%'
				""", """
				INSERT INTO reputation.portfolio_entry (id, job_id, product_id, account_id, role, job_title, product_name,
				    job_types, skill_ids, milestones_accepted, work_items_approved, milestones_handed_over, reviews_written,
				    closed_at, created_at)
				VALUES (gen_random_uuid(), '%1$s', '%2$s', '%3$s', 'MENTOR', 'Sửa sổ đặt bàn', 'Sổ đặt bàn',
				        ARRAY['BUG_FIX'], '{}', 1, NULL, 1, 4, now(), now()),
				       (gen_random_uuid(), '%1$s', '%2$s', '%4$s', 'CREW', 'Sửa sổ đặt bàn', 'Sổ đặt bàn',
				        ARRAY['BUG_FIX'], '{}', 1, 3, NULL, NULL, now(), now())
				""".formatted(JOB, PRODUCT, MENTOR, CREW), """
				INSERT INTO audit.audit_entry (id, occurred_at, actor_type, actor_account_id, action, job_id)
				VALUES ('00000000-0000-7000-8000-000000241101', now(), 'USER', '%1$s', 'work.work_item_approved', '%2$s'),
				       ('00000000-0000-7000-8000-000000241102', now(), 'USER', '%1$s', 'work.admin_read', '%2$s')
				""".formatted(MENTOR, JOB)));
		for (String schema : List.of("chat", "work", "job")) {
			statements.add("""
					INSERT INTO %1$s.admin_read_grant (grant_id, admin_account_id, job_id, items, reason_id, ends_at)
					VALUES ('%2$s', '%3$s', '%4$s', '%5$s', gen_random_uuid(), NULL),
					       ('00000000-0000-7000-8000-000000241002', '%3$s', '%4$s', '%6$s', gen_random_uuid(), now())
					""".formatted(schema, GRANT, ADMIN, JOB, DISPUTE_ITEMS.strip(), RETIRED_ONLY.strip()));
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
