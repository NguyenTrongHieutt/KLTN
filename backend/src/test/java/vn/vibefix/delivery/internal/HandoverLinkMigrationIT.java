package vn.vibefix.delivery.internal;

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

import vn.vibefix.delivery.internal.Handovers.Link;
import vn.vibefix.delivery.internal.Handovers.Pending;
import vn.vibefix.support.PostgresTestDatabase;

/**
 * Story 24.3a's forward migration (D3, FR-10, FR-32, AD-32, AD-35) on a database that still keeps a handover's
 * recording in {@code delivery.handover.recording_url}: a handover that carried a recording with its reason and one
 * screenshot, a handover with a checked preview, a done submission whose draft holds {@code recordingUrl} in the
 * shape {@link Pending} stored it before this story (with the fields earlier stories retired), and a pending one
 * whose draft holds none.
 * <p>
 * Afterwards {@code delivery.handover_link} exists with its columns, its key and its checks, the recording is one
 * VIDEO link at position 0 labelled "Video quay màn hình", {@code recording_url} is gone and the preview check no
 * longer names it, {@code delivery.handover_screenshot} is untouched, and every draft has {@code links} and no
 * {@code recordingUrl} -- read back through {@link HandoverSubmissions}' own mapper into {@link Pending}, which is
 * what reads it when the runner's copy ends. A migration test that checks only the database tests half the contract.
 */
class HandoverLinkMigrationIT {

	private static final PostgresTestDatabase database = PostgresTestDatabase.instance();

	/** The version right before this story's migration: Story 24.2b's last. */
	private static final String BEFORE = "202611191107";

	private static final String AFTER = "202611192030";

	private static final String JOB = "00000000-0000-7000-8000-000000243001";

	private static final String PRODUCT = "00000000-0000-7000-8000-000000243002";

	private static final String MENTOR = "00000000-0000-7000-8000-000000243003";

	private static final String CUSTOMER = "00000000-0000-7000-8000-000000243004";

	private static final String MILESTONE = "00000000-0000-7000-8000-000000243005";

	private static final String CRITERION = "00000000-0000-7000-8000-000000243006";

	/** The handover that carried a recording, which is also its done submission's ID. */
	private static final String RECORDED = "00000000-0000-7000-8000-000000243101";

	private static final String PREVIEWED = "00000000-0000-7000-8000-000000243102";

	private static final String PENDING = "00000000-0000-7000-8000-000000243103";

	private static final String SCREENSHOT = "00000000-0000-7000-8000-000000243201";

	private static final String RECORDING = "https://www.loom.com/share/quan-may-dat-ban";

	private static final String REASON = "Ứng dụng chỉ chạy trên máy của quán";

	@Test
	void theRecordingBecomesAVideoLinkAndEveryDraftReadsBackWithItsLinks() throws Exception {
		String db = database.createScratchDatabase();
		flyway(db, BEFORE).migrate();
		execute(db, seed());
		List<String> screenshots = rows(db, "SELECT row_to_json(s)::text FROM delivery.handover_screenshot s ORDER BY file_id");
		List<String> screenshotShape = rows(db, SHAPE.formatted("handover_screenshot"));

		flyway(db, AFTER).migrate();

		assertThat(rows(db, SHAPE.formatted("handover_link"))).as("the table's columns, in order")
			.containsExactly("handover_id uuid NO", "position integer NO", "kind text NO", "url text NO", "label text NO");
		assertThat(rows(db, """
				SELECT string_agg(a.attname, ',' ORDER BY k.ord) FROM pg_constraint c
				CROSS JOIN LATERAL unnest(c.conkey) WITH ORDINALITY AS k(attnum, ord)
				JOIN pg_attribute a ON a.attrelid = c.conrelid AND a.attnum = k.attnum
				WHERE c.conrelid = 'delivery.handover_link'::regclass AND c.contype = 'p'
				""")).as("one link per position of a handover").containsExactly("handover_id,position");
		assertThat(rows(db, "SELECT handover_id || ':' || position || ':' || kind || ':' || url || ':' || label"
				+ " FROM delivery.handover_link ORDER BY handover_id, position"))
			.as("the recording is the handover's one VIDEO link, and a preview brings none")
			.containsExactly(RECORDED + ":0:VIDEO:" + RECORDING + ":Video quay màn hình");
		assertThat(rows(db, """
				SELECT column_name FROM information_schema.columns
				WHERE table_schema = 'delivery' AND table_name = 'handover' AND column_name = 'recording_url'
				""")).as("the recording's own column is gone").isEmpty();
		assertThat(rows(db, "SELECT id || ':' || coalesce(no_preview_reason, '-') || ':' || (preview_url IS NOT NULL)"
				+ " FROM delivery.handover ORDER BY id"))
			.as("each handover keeps its reason or its preview")
			.containsExactly(RECORDED + ":" + REASON + ":false", PREVIEWED + ":-:true");
		assertThat(rows(db, "SELECT row_to_json(s)::text FROM delivery.handover_screenshot s ORDER BY file_id"))
			.as("the screenshots are untouched")
			.isEqualTo(screenshots);
		assertThat(rows(db, SHAPE.formatted("handover_screenshot"))).isEqualTo(screenshotShape);

		// The checks, each on its own: a link the handover's rules would refuse is refused by the table too.
		for (String bad : List.of("10, 'VIDEO', 'https://a.example', 'Video'", "1, 'AUDIO', 'https://a.example', 'Video'",
				"1, 'VIDEO', 'http://a.example', 'Video'", "1, 'VIDEO', 'https://a.example/có khoảng trắng', 'Video'",
				"1, 'VIDEO', 'https://a.example/" + "a".repeat(483) + "', 'Video'","1, 'DOCUMENT', 'https://a.example', ''",
				"1, 'DOCUMENT', 'https://a.example', '" + "a".repeat(81) + "'")) {
			assertThat(refused(db, "INSERT INTO delivery.handover_link (handover_id, position, kind, url, label) VALUES ('"
					+ RECORDED + "', " + bad + ")")).as(bad).contains("handover_link");
		}
		assertThat(refused(db, "INSERT INTO delivery.handover_link (handover_id, position, kind, url, label) VALUES ('"
				+ UUID.randomUUID() + "', 0, 'DOCUMENT', 'https://docs.example/bao-cao', 'Báo cáo')"))
			.as("a link hangs from a handover")
			.contains("handover_link_handover_id_fkey");
		execute(db, "INSERT INTO delivery.handover_link (handover_id, position, kind, url, label) VALUES ('" + RECORDED
				+ "', 9, 'DOCUMENT', 'https://docs.example/bao-cao', '" + "a".repeat(80) + "')");
		assertThat(refused(db, "UPDATE delivery.handover SET no_preview_reason = 'Không có máy chủ' WHERE id = '"
				+ PREVIEWED + "'")).as("a preview and a reason never come together")
			.contains("handover_preview_check");
		assertThat(refused(db, "UPDATE delivery.handover SET preview_checked_at = NULL WHERE id = '" + PREVIEWED + "'"))
			.as("a preview carries the time its check passed")
			.contains("handover_preview_check");
		assertThat(rows(db, """
				SELECT string_agg(privilege, ',' ORDER BY privilege) FROM unnest(ARRAY['SELECT', 'INSERT', 'UPDATE', 'DELETE'])
				    AS privilege WHERE has_table_privilege('%s', 'delivery.handover_link', privilege)
				""".formatted(PostgresTestDatabase.APP))).as("a link is written once and deleted, never edited")
			.containsExactly("DELETE,INSERT,SELECT");

		assertThat(rows(db, "SELECT id || ':' || (draft ? 'recordingUrl') || ':' || (draft -> 'links')::text"
				+ " FROM delivery.handover_submission ORDER BY id"))
			.as("every draft loses recordingUrl and gains links")
			.containsExactly(RECORDED + ":false:[{\"url\": \"" + RECORDING + "\", \"kind\": \"VIDEO\", \"label\": \"Video quay màn hình\"}]",
					PENDING + ":false:[]");
		Pending recorded = draft(db, RECORDED);
		assertThat(recorded.links()).as("the draft reads back with the link it meant")
			.containsExactly(new Link(Link.Kind.VIDEO, RECORDING, "Video quay màn hình"));
		assertThat(recorded.noPreviewReason()).isEqualTo(REASON);
		assertThat(recorded.previewUrl()).isNull();
		assertThat(recorded.screenshotIds()).containsExactly(UUID.fromString(SCREENSHOT));
		assertThat(recorded.criteria()).singleElement()
			.satisfies(criterion -> assertThat(criterion.note()).isEqualTo("Đạt: không còn đặt trùng."));
		assertThat(recorded.teamCode()).isNotNull();
		Pending pending = draft(db, PENDING);
		assertThat(pending.links()).as("a draft with no recording reads back with no links").isEmpty();
		assertThat(pending.previewUrl()).isEqualTo("https://so-dat-ban-gd1.vercel.app");
	}

	/** A table's columns as {@code name type nullable}, in order. */
	private static final String SHAPE = """
			SELECT column_name || ' ' || data_type || ' ' || is_nullable FROM information_schema.columns
			WHERE table_schema = 'delivery' AND table_name = '%s' ORDER BY ordinal_position
			""";

	private static String[] seed() {
		return new String[] { """
				INSERT INTO delivery.handover (id, job_id, product_id, milestone_id, milestone_number, milestone_title,
				    round, mentor_account_id, status, job_version, transition_id, check_by, created_at, decided_at, decided_by,
				    recording_url, no_preview_reason)
				VALUES ('%1$s', '%3$s', '%4$s', '%5$s', 1, 'Chặn đặt trùng bàn', 1, '%6$s', 'ACCEPTED', 8, gen_random_uuid(),
				        now(), now(), now(), '%7$s', '%8$s', '%9$s'),
				       ('%2$s', '%3$s', '%4$s', '%5$s', 1, 'Chặn đặt trùng bàn', 2, '%6$s', 'SUBMITTED', 10,
				        gen_random_uuid(), now(), now(), NULL, NULL, NULL, NULL)
				""".formatted(RECORDED, PREVIEWED, JOB, PRODUCT, MILESTONE, MENTOR, CUSTOMER, RECORDING, REASON), """
				UPDATE delivery.handover SET preview_url = 'https://so-dat-ban-gd1.vercel.app', preview_checked_at = now()
				WHERE id = '%s'
				""".formatted(PREVIEWED), """
				INSERT INTO delivery.handover_screenshot (file_id, job_id, product_id, account_id, file_name, size_bytes,
				    handover_id, position, uploaded_at)
				VALUES ('%s', '%s', '%s', '%s', 'dat-ban.png', 2048, '%s', 0, now())
				""".formatted(SCREENSHOT, JOB, PRODUCT, MENTOR, RECORDED), submission(RECORDED, "DONE", draft(RECORDED,
						"null", "\"" + RECORDING + "\"", "\"" + REASON + "\"", "[\"" + SCREENSHOT + "\"]")),
				submission(PENDING, "PENDING",
						draft(PENDING, "\"https://so-dat-ban-gd1.vercel.app\"", "null", "null", "[]")) };
	}

	private static String submission(String id, String status, String draft) {
		return """
				INSERT INTO delivery.handover_submission (id, job_id, product_id, milestone_id, milestone_number,
				    mentor_account_id, customer_account_id, job_version, range_start, handover_commit, draft, status,
				    created_at, finished_at)
				VALUES ('%s', '%s', '%s', '%s', 1, '%s', '%s', 8, repeat('1f', 20), repeat('2e', 20), '%s'::jsonb, '%s', now(),
				        %s)
				""".formatted(id, JOB, PRODUCT, MILESTONE, MENTOR, CUSTOMER, draft, status,
				"PENDING".equals(status) ? "NULL" : "now()");
	}

	/**
	 * A draft as {@link Pending} serialised it before this story, with the fields earlier stories retired that a draft
	 * stored then still holds ({@code itemNotes}, {@code reportPageId}, {@code reportVersionNo}).
	 */
	private static String draft(String id, String previewUrl, String recordingUrl, String reason, String screenshots) {
		return """
				{"id": "%1$s", "jobId": "%2$s", "productId": "%3$s", "customerId": "%4$s", "mentorId": "%5$s",
				 "version": 8, "milestoneId": "%6$s", "milestoneNumber": 1, "milestoneTitle": "Chặn đặt trùng bàn",
				 "criteria": [{"criterionId": "%7$s", "number": 1, "statement": "Hai khách không đặt trùng bàn",
				               "checkSteps": "Đặt hai lần cùng giờ", "met": true, "note": "Đạt: không còn đặt trùng."}],
				 "previewUrl": %8$s, "recordingUrl": %9$s, "noPreviewReason": %10$s, "screenshotIds": %11$s,
				 "teamCode": {"accountId": "%5$s", "noticeVersion": "1.0", "confirmedAt": "2026-11-18T09:12:27.896597022Z"},
				 "itemNotes": [], "reportPageId": null, "reportVersionNo": null}
				""".formatted(id, JOB, PRODUCT, CUSTOMER, MENTOR, MILESTONE, CRITERION, previewUrl, recordingUrl, reason,
				screenshots);
	}

	/** The submission's draft, read as the copy's end reads it. */
	private static Pending draft(String db, String id) throws SQLException {
		String json = rows(db, "SELECT draft::text FROM delivery.handover_submission WHERE id = '" + id + "'").getFirst();
		return HandoverSubmissions.JSON.readValue(json, Pending.class);
	}

	/** The message of the error {@code sql} raises; it must raise one. */
	private static String refused(String db, String sql) throws SQLException {
		try {
			execute(db, sql);
		}
		catch (SQLException refused) {
			return refused.getMessage();
		}
		throw new AssertionError("Expected a refusal: " + sql);
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
