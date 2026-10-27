package vn.vibefix.admin.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.Timestamp;
import java.time.Duration;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.session.Session;
import org.springframework.session.SessionRepository;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import vn.vibefix.identity.internal.AdministratorTestSupport;
import vn.vibefix.identity.internal.AdministratorTestSupport.Administrator;
import vn.vibefix.identity.internal.IdentityTestSupport;
import vn.vibefix.shared.Role;
import vn.vibefix.support.IntegrationTest;

/**
 * ADM-2's "Tài khoản có thể cùng một người" (AD-19, AD-8, Story 19.7): the accounts that share a live
 * person-link signal with the one an Administrator opens, each with the kinds they share, its state, and
 * never an HMAC. Every opened read is audited as {@code identity.person_links_viewed}, an empty one too; the
 * answer of a lock or an unlock is not.
 */
class PersonLinksIT extends IntegrationTest {

	@Autowired
	private MockMvc mvc;

	@Autowired
	private JdbcClient jdbc;

	@Autowired
	private SessionRepository<? extends Session> sessions;

	@Autowired
	private ApplicationContext context;

	@Test
	void theAccountsSharingASignalAreListedByKindAndStateWithoutTheHmac() throws Exception {
		Administrator ha = AdministratorTestSupport.create(this.context, "ha.phuong@vibefix.test", "Hà Phương");
		UUID lan = IdentityTestSupport.insertGoogleAccount(this.jdbc, "google-lan", "3.1");
		UUID lan2 = IdentityTestSupport.insertGoogleAccount(this.jdbc, "google-lan-2", "3.1");
		UUID locked = IdentityTestSupport.insertGoogleAccount(this.jdbc, "google-lan-3", "3.1");
		UUID erased = IdentityTestSupport.insertAccount(this.jdbc, Role.STUDENT, null);
		UUID stranger = IdentityTestSupport.insertGoogleAccount(this.jdbc, "google-other", "3.1");
		String email = IdentityTestSupport.fakeHmac("lan@gmail.test");
		String linkedin = IdentityTestSupport.fakeHmac("linkedin.com/in/lan");
		signal(lan, "EMAIL", "GOOGLE_EMAIL", email);
		signal(lan2, "EMAIL", "GITHUB_EMAIL", email);
		signal(lan, "LINKEDIN", "LINKEDIN", linkedin);
		signal(lan2, "LINKEDIN", "LINKEDIN", linkedin);
		signal(locked, "EMAIL", "GOOGLE_EMAIL", email);
		signal(erased, "EMAIL", "SCHOOL_EMAIL", email);
		IdentityTestSupport.lock(this.jdbc, locked, "Vi phạm");
		// Created later, in this order; an erased account keeps its signals 24 months.
		later(locked, 1);
		later(erased, 2);
		this.jdbc.sql("""
				UPDATE identity.account SET erased_at = now(), github_user_id = NULL, github_login = NULL WHERE id = :id
				""").param("id", erased).update();
		signal(stranger, "EMAIL", "GOOGLE_EMAIL", IdentityTestSupport.fakeHmac("other@gmail.test"));

		String body = this.mvc.perform(get("/api/v1/admin/users/" + lan).with(admin(ha)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.personLinks.length()").value(3))
			.andExpect(jsonPath("$.personLinks[0].accountId").value(lan2.toString()))
			.andExpect(jsonPath("$.personLinks[0].name").value("Người dùng google-lan-2"))
			.andExpect(jsonPath("$.personLinks[0].kinds[0]").value("EMAIL"))
			.andExpect(jsonPath("$.personLinks[0].kinds[1]").value("LINKEDIN"))
			.andExpect(jsonPath("$.personLinks[0].locked").value(false))
			.andExpect(jsonPath("$.personLinks[0].erased").value(false))
			.andExpect(jsonPath("$.personLinks[0].createdAt").isNotEmpty())
			.andExpect(jsonPath("$.personLinks[1].accountId").value(locked.toString()))
			.andExpect(jsonPath("$.personLinks[1].locked").value(true))
			.andExpect(jsonPath("$.personLinks[1].kinds.length()").value(1))
			.andExpect(jsonPath("$.personLinks[2].accountId").value(erased.toString()))
			.andExpect(jsonPath("$.personLinks[2].erased").value(true))
			.andExpect(jsonPath("$.personLinks[2].name").doesNotExist())
			.andReturn()
			.getResponse()
			.getContentAsString();
		assertThat(body).doesNotContain(email).doesNotContain(linkedin).doesNotContain(stranger.toString());
		assertThat(audits(lan)).isOne();
	}

	@Test
	void everyOpenedReadIsAuditedAnEmptyOneTooButNotALockOrAnUnlock() throws Exception {
		Administrator ha = AdministratorTestSupport.create(this.context, "ha.phuong@vibefix.test", "Hà Phương");
		UUID stranger = IdentityTestSupport.insertGoogleAccount(this.jdbc, "google-other", "3.1");

		this.mvc.perform(get("/api/v1/admin/users/" + stranger).with(admin(ha)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.personLinks.length()").value(0));
		assertThat(audits(stranger)).as("an empty read is a read").isOne();

		command(ha, stranger, "lock").andExpect(status().isOk()).andExpect(jsonPath("$.personLinks.length()").value(0));
		this.clock.advance(Duration.ofMinutes(5));
		command(ha, stranger, "unlock").andExpect(status().isOk());
		assertThat(audits(stranger)).as("a command's answer is no opened read").isOne();
		// The lock's consequences run after commit; let them finish before the database is reset.
		await().atMost(Duration.ofSeconds(10))
			.until(() -> this.jdbc.sql("SELECT count(*) FROM platform.event_publication WHERE completion_date IS NULL")
				.query(Long.class)
				.single() == 0);
	}

	private void signal(UUID account, String kind, String source, String hmac) {
		this.jdbc.sql("""
				INSERT INTO identity.person_signal (id, account_id, kind, source, hmac, key_version, recorded_at)
				VALUES (:id, :account, :kind, :source, :hmac, 1, :now)
				""")
			.param("id", UUID.randomUUID())
			.param("account", account)
			.param("kind", kind)
			.param("source", source)
			.param("hmac", hmac)
			.param("now", Timestamp.from(this.clock.instant()))
			.update();
	}

	private void later(UUID account, int days) {
		this.jdbc.sql("UPDATE identity.account SET created_at = created_at + make_interval(days => :days) WHERE id = :id")
			.param("days", days)
			.param("id", account)
			.update();
	}

	private long audits(UUID account) {
		return this.jdbc.sql("""
				SELECT count(*) FROM audit.audit_entry e JOIN audit.audit_entry_object o ON o.entry_id = e.id
				WHERE e.action = 'identity.person_links_viewed' AND o.object_type = 'identity.account'
				  AND o.object_id = :account
				""").param("account", account).query(Long.class).single();
	}

	private ResultActions command(Administrator administrator, UUID account, String action) throws Exception {
		return this.mvc.perform(post("/api/v1/admin/users/" + account + "/" + action).with(admin(administrator))
			.with(csrf())
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"reason\":\"Kiểm tra\"}"));
	}

	private RequestPostProcessor admin(Administrator administrator) {
		return IdentityTestSupport.signedIn(this.sessions, administrator.accountId(), Role.ADMINISTRATOR, this.clock.instant());
	}

}
