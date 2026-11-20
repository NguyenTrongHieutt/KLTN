package vn.vibefix.chat.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.session.Session;
import org.springframework.session.SessionRepository;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import vn.vibefix.identity.internal.IdentityTestSupport;
import vn.vibefix.identity.internal.Role;
import vn.vibefix.notification.internal.TestMailConfiguration;
import vn.vibefix.support.MutableClock;
import vn.vibefix.support.PostgresTestDatabase;
import vn.vibefix.support.TestClockConfiguration;
import vn.vibefix.support.TestOperationsConfiguration;

/**
 * Story 24.4a's forward migration (D5, FR-46, FR-77, AD-18, NFR-10) on a database whose closed Job Chat still holds a
 * message the retired check stored: masked, with its masked counts, flagged as sent despite the warning, checked again
 * at the Job's end, and carrying an attachment, beside a plain message.
 * <p>
 * Right after the migration the four columns of the check and the rewrite are gone, the runtime role holds UPDATE on
 * no column of {@code chat.message} and keeps SELECT, INSERT and DELETE, and every body and attachment is unchanged.
 * Then the application, started on that very database, serves the masked message through
 * {@code GET /api/v1/jobs/{jobId}/chat/messages} and {@code GET /api/v1/chat-messages/{messageId}} with its body and
 * attachment and no masked count. A migration test that checks only the database tests half the contract, so this
 * class has its own context on its own database, which no other test shares; the context is closed after it.
 */
@SpringBootTest(properties = { "management.health.sandbox.enabled=false", "vibefix.scheduling.enabled=false",
		"vibefix.files.root=target/test-files", "vibefix.payment.refund-gateway=ledger" })
@AutoConfigureMockMvc
@ActiveProfiles("dev")
@Import({ TestClockConfiguration.class, TestMailConfiguration.class, TestOperationsConfiguration.class })
@DirtiesContext
class RetireChatTextCheckMigrationIT {

	private static final PostgresTestDatabase database = PostgresTestDatabase.instance();

	/** The version right before this story's migration: Story 24.3a's. */
	private static final String BEFORE = "202611192030";

	private static final String AFTER = "202611201100";

	private static final UUID CHAT = UUID.fromString("00000000-0000-7000-8000-000000244101");

	private static final UUID MASKED = UUID.fromString("00000000-0000-7000-8000-000000244201");

	private static final UUID PLAIN = UUID.fromString("00000000-0000-7000-8000-000000244202");

	private static final UUID FILE = UUID.fromString("00000000-0000-7000-8000-000000244301");

	/** What "Che và gửi", or the Job's end, left of a likely credential: the masked text, no original. */
	private static final String MASKED_BODY = "Khóa thử của tôi là sk_t••••••••3f9A";

	private static final String PLAIN_BODY = "Cảm ơn chị, em đã nhận.";

	/** Each message as {@code id:body:attachment:sentAt}, oldest ID first. */
	private static final String MESSAGE_ROWS = """
			SELECT id || ':' || body || ':' || coalesce(attachment_file_id::text || '/' || attachment_name || '/'
			       || attachment_media_type || '/' || attachment_size, '-') || ':' || sent_at
			FROM chat.message ORDER BY id
			""";

	/** The four columns of the check and the rewrite that are still there. */
	private static final String RETIRED_COLUMNS = """
			SELECT column_name FROM information_schema.columns WHERE table_schema = 'chat' AND table_name = 'message'
			  AND column_name IN ('masked_credentials', 'masked_personal', 'sent_despite_warning', 'redacted_at')
			ORDER BY column_name
			""";

	/** Every column of {@code chat.message} the runtime role may update, through a column or a table grant. */
	private static final String UPDATABLE_COLUMNS = """
			SELECT column_name FROM information_schema.columns WHERE table_schema = 'chat' AND table_name = 'message'
			  AND has_column_privilege('%s', 'chat.message', column_name, 'UPDATE')
			ORDER BY column_name
			""".formatted(PostgresTestDatabase.APP);

	/** The runtime role's privileges on {@code chat.message}. */
	private static final String TABLE_PRIVILEGES = """
			SELECT string_agg(privilege, ',' ORDER BY privilege) FROM unnest(ARRAY['SELECT', 'INSERT', 'UPDATE', 'DELETE'])
			    AS privilege WHERE has_table_privilege('%s', 'chat.message', privilege)
			""".formatted(PostgresTestDatabase.APP);

	/**
	 * The scratch database: migrated to {@link #BEFORE}, seeded, read, migrated to {@link #AFTER} and read again, once,
	 * before the context starts on it; the context's own Flyway then applies whatever is newer.
	 */
	private static final class Scratch {

		static final String DB = database.createScratchDatabase();

		static final UUID LAN;

		static final UUID HUNG;

		static final UUID JOB;

		static final List<String> UPDATABLE_BEFORE;

		static final List<String> MESSAGES_BEFORE;

		static final List<String> RETIRED_AFTER;

		static final List<String> UPDATABLE_AFTER;

		static final List<String> PRIVILEGES_AFTER;

		static final List<String> MESSAGES_AFTER;

		static {
			try {
				flyway(DB, BEFORE).migrate();
				JdbcClient jdbc = JdbcClient.create(new DriverManagerDataSource(database.jdbcUrl(DB),
						PostgresTestDatabase.MIGRATOR, database.migratorPassword()));
				LAN = IdentityTestSupport.insertAccount(jdbc, Role.CUSTOMER, null);
				jdbc.sql("""
						INSERT INTO identity.profile (account_id, kind, display_name, contact_email, updated_at)
						VALUES (:id, 'INDIVIDUAL', 'Lan Nguyễn', 'lan@example.com', now())
						""").param("id", LAN).update();
				HUNG = IdentityTestSupport.insertAccount(jdbc, Role.MENTOR, null);
				Instant at = MutableClock.DEFAULT_INSTANT;
				UUID product = ChatTestSupport.product(jdbc, LAN);
				JOB = ChatTestSupport.job(jdbc, product, LAN, "CLOSED", HUNG, at);
				seed(jdbc, product, at);
				UPDATABLE_BEFORE = rows(DB, UPDATABLE_COLUMNS);
				MESSAGES_BEFORE = rows(DB, MESSAGE_ROWS);

				flyway(DB, AFTER).migrate();

				RETIRED_AFTER = rows(DB, RETIRED_COLUMNS);
				UPDATABLE_AFTER = rows(DB, UPDATABLE_COLUMNS);
				PRIVILEGES_AFTER = rows(DB, TABLE_PRIVILEGES);
				MESSAGES_AFTER = rows(DB, MESSAGE_ROWS);
			}
			catch (SQLException ex) {
				throw new IllegalStateException("The scratch database could not be prepared", ex);
			}
		}

		private static void seed(JdbcClient jdbc, UUID product, Instant at) {
			jdbc.sql("UPDATE job.job SET closing_at = :at, closed_at = :at WHERE id = :job")
				.param("at", Timestamp.from(at))
				.param("job", JOB)
				.update();
			jdbc.sql("""
					INSERT INTO files.stored_file (id, purpose, account_id, file_name, media_type, kind, size_bytes, sha256,
					    stored_at)
					VALUES (:file, 'chat.attachment', :lan, 'bien-ban.pdf', 'application/pdf', 'PDF', 1024, repeat('b', 64), :at)
					""").param("file", FILE).param("lan", LAN).param("at", Timestamp.from(at)).update();
			jdbc.sql("""
					INSERT INTO chat.chat (id, job_id, product_id, kind, opened_at, closed_at)
					VALUES (:chat, :job, :product, 'JOB', :at, :closed)
					""")
				.param("chat", CHAT)
				.param("job", JOB)
				.param("product", product)
				.param("at", Timestamp.from(at))
				.param("closed", Timestamp.from(at.plusSeconds(120)))
				.update();
			jdbc.sql("""
					INSERT INTO chat.message (id, chat_id, author_account_id, body, masked_credentials, masked_personal,
					    sent_despite_warning, attachment_file_id, attachment_name, attachment_media_type, attachment_size,
					    sent_at, redacted_at)
					VALUES (:masked, :chat, :lan, :maskedBody, 1, 2, TRUE, :file, 'bien-ban.pdf', 'application/pdf', 1024,
					        :at, :redacted),
					       (:plain, :chat, :hung, :plainBody, 0, 0, FALSE, NULL, NULL, NULL, NULL, :later, NULL)
					""")
				.param("masked", MASKED)
				.param("plain", PLAIN)
				.param("chat", CHAT)
				.param("lan", LAN)
				.param("hung", HUNG)
				.param("maskedBody", MASKED_BODY)
				.param("plainBody", PLAIN_BODY)
				.param("file", FILE)
				.param("at", Timestamp.from(at.plusSeconds(10)))
				.param("later", Timestamp.from(at.plusSeconds(20)))
				.param("redacted", Timestamp.from(at.plusSeconds(120)))
				.update();
		}

	}

	@DynamicPropertySource
	static void scratchDatabase(DynamicPropertyRegistry registry) {
		registry.add("spring.datasource.url", () -> database.jdbcUrl(Scratch.DB));
		registry.add("spring.datasource.username", () -> PostgresTestDatabase.APP);
		registry.add("spring.datasource.password", database::appPassword);
		registry.add("spring.flyway.user", () -> PostgresTestDatabase.MIGRATOR);
		registry.add("spring.flyway.password", database::migratorPassword);
		registry.add("identity-token-key-1", () -> PostgresTestDatabase.TEST_TOKEN_KEY);
		registry.add("identity-person-signal-key-1", () -> PostgresTestDatabase.TEST_SIGNAL_KEY);
	}

	@Autowired
	private MockMvc mvc;

	@Autowired
	private SessionRepository<? extends Session> sessions;

	@Test
	void theFourColumnsAndTheirUpdateGrantGoAndTheMaskedMessageReadsBackThroughTheApi() throws Exception {
		assertThat(Scratch.UPDATABLE_BEFORE).as("the redaction's column grant, before")
			.containsExactly("body", "masked_credentials", "masked_personal", "redacted_at");
		assertThat(Scratch.RETIRED_AFTER).as("the four columns of the check and the rewrite are gone").isEmpty();
		assertThat(Scratch.UPDATABLE_AFTER).as("no column of a message may be rewritten").isEmpty();
		assertThat(Scratch.PRIVILEGES_AFTER).as("a message is written once and deleted, never edited")
			.containsExactly("DELETE,INSERT,SELECT");
		assertThat(Scratch.MESSAGES_AFTER).as("every body and attachment is unchanged")
			.isEqualTo(Scratch.MESSAGES_BEFORE)
			.hasSize(2)
			.anySatisfy(row -> assertThat(row).startsWith(MASKED + ":" + MASKED_BODY + ":" + FILE + "/bien-ban.pdf/"));
		assertThat(rows(Scratch.DB, "SELECT count(*)::text FROM platform.flyway_schema_history WHERE version = '"
				+ AFTER + "' AND success")).as("the migration is in the history the application started on")
			.containsExactly("1");

		this.mvc.perform(get("/api/v1/jobs/" + Scratch.JOB + "/chat/messages").with(as(Scratch.LAN, Role.CUSTOMER)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.items.length()").value(2))
			.andExpect(jsonPath("$.items[0].id").value(MASKED.toString()))
			.andExpect(jsonPath("$.items[0].author").value("Lan Nguyễn"))
			.andExpect(jsonPath("$.items[0].mine").value(true))
			.andExpect(jsonPath("$.items[0].body").value(MASKED_BODY))
			.andExpect(jsonPath("$.items[0].attachment.fileId").value(FILE.toString()))
			.andExpect(jsonPath("$.items[0].attachment.name").value("bien-ban.pdf"))
			.andExpect(jsonPath("$.items[0].attachment.mediaType").value("application/pdf"))
			.andExpect(jsonPath("$.items[0].attachment.size").value(1024))
			.andExpect(jsonPath("$.items[0].maskedCredentials").doesNotExist())
			.andExpect(jsonPath("$.items[0].maskedPersonal").doesNotExist())
			.andExpect(jsonPath("$.items[1].id").value(PLAIN.toString()))
			.andExpect(jsonPath("$.items[1].body").value(PLAIN_BODY))
			.andExpect(jsonPath("$.items[1].attachment").doesNotExist())
			.andExpect(jsonPath("$.hasOlder").value(false));
		this.mvc.perform(get("/api/v1/chat-messages/" + MASKED).with(as(Scratch.HUNG, Role.MENTOR)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.mine").value(false))
			.andExpect(jsonPath("$.body").value(MASKED_BODY))
			.andExpect(jsonPath("$.attachment.name").value("bien-ban.pdf"))
			.andExpect(jsonPath("$.maskedCredentials").doesNotExist());
		this.mvc.perform(get("/api/v1/jobs/" + Scratch.JOB + "/chat").with(as(Scratch.LAN, Role.CUSTOMER)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.readOnly").value(true));
	}

	private RequestPostProcessor as(UUID account, Role role) {
		return IdentityTestSupport.signedIn(this.sessions, account, role, MutableClock.DEFAULT_INSTANT);
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
