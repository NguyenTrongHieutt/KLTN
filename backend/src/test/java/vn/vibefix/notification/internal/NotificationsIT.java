package vn.vibefix.notification.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.Timestamp;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import vn.vibefix.identity.internal.IdentityTestSupport;
import vn.vibefix.identity.internal.Role;
import vn.vibefix.notification.Audience;
import vn.vibefix.notification.NotificationRequest;
import vn.vibefix.notification.Notifications;
import vn.vibefix.notification.Recipient;
import vn.vibefix.support.IntegrationTest;

/** AD-27, AD-20, FR-48: sending notifications, and reading them on ALL-1 and the bell. */
class NotificationsIT extends IntegrationTest {

	@Autowired
	private Notifications notifications;

	@Autowired
	private NotificationPurge purge;

	@Autowired
	private JdbcClient jdbc;

	@Autowired
	private MockMvc mvc;

	@Autowired
	private PlatformTransactionManager transactionManager;

	@Test
	void anInAppAndEmailTypeWritesTheItemAndEmailsTheRecipientsAddressToTheSamePath() {
		UUID lan = account(Role.CUSTOMER, "lan@example.test");
		UUID cause = UUID.randomUUID();

		send("test.subject_update", cause, List.of(new Recipient(lan, Audience.SUBJECT)), Map.of("name", "Lan"),
				"/viec-can-lam", lan);
		send("test.subject_update", cause, List.of(new Recipient(lan, Audience.SUBJECT)), Map.of("name", "Lan"),
				"/viec-can-lam", lan);

		assertThat(this.jdbc.sql("""
				SELECT type || ' ' || params::text || ' ' || link || ' ' || (read_at IS NULL)
				FROM notification.notification WHERE account_id = :lan
				""").param("lan", lan).query(String.class).list())
			.as("a repeated send adds nothing")
			.containsExactly("test.subject_update {\"name\": \"Lan\"} /viec-can-lam true");
		assertThat(this.jdbc.sql("SELECT payload::text FROM operations.external_operation").query(String.class).list())
			.singleElement()
			.satisfies(payload -> assertThat(payload).contains("\"template\": \"test.subject_update\"")
				.contains("\"link\": \"/viec-can-lam\""));
	}

	@Test
	void aRecipientWithoutAnAddressGetsTheItemOnlyAndAnEmailOnlyTypeNoItem() {
		UUID minh = account(Role.STUDENT, null);
		UUID lan = account(Role.CUSTOMER, "lan@example.test");

		send("test.subject_update", UUID.randomUUID(), List.of(new Recipient(minh, Audience.SUBJECT)),
				Map.of("name", "Minh"), "/viec-can-lam", minh);
		send("test.subject_email", UUID.randomUUID(), List.of(new Recipient(lan, Audience.SUBJECT)), Map.of(),
				"/viec-can-lam", lan);

		assertThat(this.jdbc.sql("SELECT account_id FROM notification.notification").query(UUID.class).list())
			.containsExactly(minh);
		assertThat(this.jdbc.sql("SELECT account_id FROM operations.external_operation").query(UUID.class).list())
			.containsExactly(lan);
	}

	@Test
	void aRecipientOutsideTheTypesAudienceClassesOrUnconfirmedIsRefusedAndNothingIsWritten() {
		UUID lan = account(Role.CUSTOMER, "lan@example.test");
		UUID admin = account(Role.ADMINISTRATOR, "admin@example.test");

		assertThatThrownBy(() -> send("test.subject_update", UUID.randomUUID(),
				List.of(new Recipient(lan, Audience.SUBJECT), new Recipient(admin, Audience.ADMIN)), Map.of("name", "x"),
				"/viec-can-lam", lan))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("does not reach the audience class ADMIN");
		assertThatThrownBy(() -> send("test.admin_alert", UUID.randomUUID(), List.of(new Recipient(lan, Audience.ADMIN)),
				Map.of(), "/viec-can-lam", null))
			.as("a Customer is no Administrator")
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("not confirmed as ADMIN");
		assertThatThrownBy(() -> send("test.job_update", UUID.randomUUID(), List.of(new Recipient(lan, Audience.CUSTOMER)),
				Map.of(), "/viec-can-lam", null))
			.as("a Job-scoped class needs the Job")
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> inTransaction(() -> this.notifications.send(new NotificationRequest("test.job_update",
				UUID.randomUUID(), List.of(new Recipient(lan, Audience.CUSTOMER)), Map.of(), "/viec-can-lam",
				UUID.randomUUID(), null))))
			.as("nobody is the CUSTOMER of a Job that does not exist")
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("not confirmed as CUSTOMER");
		assertThatThrownBy(() -> send("test.subject_info", UUID.randomUUID(), List.of(new Recipient(lan, Audience.SUBJECT)),
				Map.of(), "/viec-can-lam", null))
			.as("SUBJECT needs its record")
			.isInstanceOf(IllegalArgumentException.class);

		assertThat(this.jdbc.sql("SELECT count(*) FROM notification.notification").query(Long.class).single()).isZero();
		assertThat(this.jdbc.sql("SELECT count(*) FROM operations.external_operation").query(Long.class).single())
			.isZero();
	}

	@Test
	void anAdministratorIsConfirmedByIdentity() {
		UUID admin = account(Role.ADMINISTRATOR, "admin@example.test");

		send("test.admin_alert", UUID.randomUUID(), List.of(new Recipient(admin, Audience.ADMIN)), Map.of(),
				"/viec-can-lam", null);

		assertThat(this.jdbc.sql("SELECT account_id FROM notification.notification").query(UUID.class).single())
			.isEqualTo(admin);
	}

	@Test
	void parametersMustBeExactlyTheDeclaredOnesAndTheLinkAPlatformPath() {
		UUID lan = account(Role.CUSTOMER, null);
		List<Recipient> to = List.of(new Recipient(lan, Audience.SUBJECT));

		assertThatThrownBy(() -> send("test.subject_update", UUID.randomUUID(), to, Map.of(), "/a", lan))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> send("test.subject_update", UUID.randomUUID(), to,
				Map.of("name", "x", "secret", "y"), "/a", lan))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> send("test.subject_update", UUID.randomUUID(), to, Map.of("name", "x"),
				"https://evil.example/", lan))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("not a platform path");
		assertThatThrownBy(() -> send("test.unknown", UUID.randomUUID(), to, Map.of(), "/a", lan))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> this.notifications.send(new NotificationRequest("test.subject_info", UUID.randomUUID(),
				to, Map.of(), "/a", null, lan)))
			.isInstanceOf(IllegalTransactionStateException.class);
	}

	@Test
	void theCallerReadsOnlyTheirOwnNotificationsOfTheLast90DaysNewestFirst() throws Exception {
		UUID lan = account(Role.CUSTOMER, null);
		UUID minh = account(Role.STUDENT, null);
		send("test.subject_info", UUID.randomUUID(), List.of(new Recipient(lan, Audience.SUBJECT)), Map.of(), "/a", lan);
		this.clock.advance(Duration.ofMinutes(1));
		send("test.subject_update", UUID.randomUUID(), List.of(new Recipient(lan, Audience.SUBJECT)),
				Map.of("name", "mới"), "/b", lan);
		send("test.subject_info", UUID.randomUUID(), List.of(new Recipient(minh, Audience.SUBJECT)), Map.of(), "/c", minh);
		insertOld(lan, 91);

		this.mvc.perform(get("/api/v1/notifications").with(as(lan, Role.CUSTOMER)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.total").value(2))
			.andExpect(jsonPath("$.items.length()").value(2))
			.andExpect(jsonPath("$.items[0].type").value("test.subject_update"))
			.andExpect(jsonPath("$.items[0].params.name").value("mới"))
			.andExpect(jsonPath("$.items[0].icon").value("bell"))
			.andExpect(jsonPath("$.items[0].link").value("/b"))
			.andExpect(jsonPath("$.items[0].read").value(false))
			.andExpect(jsonPath("$.items[1].icon").value("info"));
		this.mvc.perform(get("/api/v1/notifications/unread-count").with(as(lan, Role.CUSTOMER)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.count").value(2));
	}

	@Test
	void readingMarksOnlyTheCallersOwnItem() throws Exception {
		UUID lan = account(Role.CUSTOMER, null);
		UUID minh = account(Role.STUDENT, null);
		send("test.subject_info", UUID.randomUUID(), List.of(new Recipient(lan, Audience.SUBJECT)), Map.of(), "/a", lan);
		send("test.subject_info", UUID.randomUUID(), List.of(new Recipient(lan, Audience.SUBJECT)), Map.of(), "/b", lan);
		send("test.subject_info", UUID.randomUUID(), List.of(new Recipient(minh, Audience.SUBJECT)), Map.of(), "/c", minh);
		UUID lansFirst = idOf(lan, "/a");
		UUID minhs = idOf(minh, "/c");

		this.mvc.perform(post("/api/v1/notifications/{id}/read", minhs).with(as(lan, Role.CUSTOMER)).with(csrf()))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.code").value("NOT_FOUND"));
		this.mvc.perform(post("/api/v1/notifications/{id}/read", lansFirst).with(as(lan, Role.CUSTOMER)).with(csrf()))
			.andExpect(status().isNoContent());
		this.mvc.perform(get("/api/v1/notifications/unread-count").with(as(lan, Role.CUSTOMER)))
			.andExpect(jsonPath("$.count").value(1));
		this.mvc.perform(post("/api/v1/notifications/read-all").with(as(lan, Role.CUSTOMER)).with(csrf()))
			.andExpect(status().isNoContent());

		assertThat(this.jdbc.sql("SELECT account_id FROM notification.notification WHERE read_at IS NULL")
			.query(UUID.class)
			.list()).containsExactly(minh);
	}

	@Test
	void thePurgeDeletesNotificationsAfter90DaysAndClosedToDosAfter90DaysClosed() {
		UUID lan = account(Role.CUSTOMER, null);
		insertOld(lan, 91);
		insertOld(lan, 89);

		assertThat(this.purge.purge()).isOne();
		assertThat(this.jdbc.sql("SELECT count(*) FROM notification.notification").query(Long.class).single()).isOne();
	}

	private void send(String type, UUID cause, List<Recipient> recipients, Map<String, String> params, String link,
			UUID subject) {
		inTransaction(() -> this.notifications
			.send(new NotificationRequest(type, cause, recipients, params, link, null, subject)));
	}

	private void inTransaction(Runnable work) {
		new TransactionTemplate(this.transactionManager).executeWithoutResult(status -> work.run());
	}

	private UUID account(Role role, String email) {
		UUID id = IdentityTestSupport.insertAccount(this.jdbc, role, null);
		if (email != null) {
			this.jdbc.sql("UPDATE identity.account SET github_email = :email WHERE id = :id")
				.param("email", email)
				.param("id", id)
				.update();
		}
		return id;
	}

	private void insertOld(UUID account, int days) {
		this.jdbc.sql("""
				INSERT INTO notification.notification (id, account_id, type, causing_record_id, params, link, created_at)
				VALUES (:id, :account, 'test.subject_info', :cause, '{}', '/cu', :at)
				""")
			.param("id", UUID.randomUUID())
			.param("account", account)
			.param("cause", UUID.randomUUID())
			.param("at", Timestamp.from(this.clock.instant().minus(Duration.ofDays(days))))
			.update();
	}

	private UUID idOf(UUID account, String link) {
		return this.jdbc.sql("SELECT id FROM notification.notification WHERE account_id = :account AND link = :link")
			.param("account", account)
			.param("link", link)
			.query(UUID.class)
			.single();
	}

	private static RequestPostProcessor as(UUID account, Role role) {
		return authentication(IdentityTestSupport.authentication(account, role));
	}

}
