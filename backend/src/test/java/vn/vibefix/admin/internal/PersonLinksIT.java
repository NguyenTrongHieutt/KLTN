package vn.vibefix.admin.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.Timestamp;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.session.Session;
import org.springframework.session.SessionRepository;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import vn.vibefix.identity.internal.AdministratorTestSupport;
import vn.vibefix.identity.internal.AdministratorTestSupport.Administrator;
import vn.vibefix.identity.internal.IdentityTestSupport;
import vn.vibefix.shared.Role;
import vn.vibefix.support.IntegrationTest;

/**
 * ADM-2's "Tài khoản có thể cùng một người" (AD-19, AD-8, Story 19.7): the accounts that share a live
 * person-link signal with the one an Administrator opens, each with the kinds they share and never an
 * HMAC; a read that finds any is audited as {@code identity.person_links_viewed}.
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
	void theAccountsSharingASignalAreListedByKindWithoutTheHmacAndTheReadIsAudited() throws Exception {
		Administrator ha = AdministratorTestSupport.create(this.context, "ha.phuong@vibefix.test", "Hà Phương");
		UUID lan = IdentityTestSupport.insertGoogleAccount(this.jdbc, "google-lan", "3.0");
		UUID lan2 = IdentityTestSupport.insertGoogleAccount(this.jdbc, "google-lan-2", "3.0");
		UUID erased = IdentityTestSupport.insertAccount(this.jdbc, Role.STUDENT, null);
		UUID stranger = IdentityTestSupport.insertGoogleAccount(this.jdbc, "google-other", "3.0");
		String email = IdentityTestSupport.fakeHmac("lan@gmail.test");
		String linkedin = IdentityTestSupport.fakeHmac("linkedin.com/in/lan");
		signal(lan, "EMAIL", "GOOGLE_EMAIL", email);
		signal(lan2, "EMAIL", "GITHUB_EMAIL", email);
		signal(lan, "LINKEDIN", "LINKEDIN", linkedin);
		signal(lan2, "LINKEDIN", "LINKEDIN", linkedin);
		signal(erased, "EMAIL", "SCHOOL_EMAIL", email);
		// Created after the others, so it is listed last; an erased account keeps its signals 24 months.
		this.jdbc.sql("""
				UPDATE identity.account SET erased_at = now(), github_user_id = NULL, github_login = NULL,
				    created_at = created_at + INTERVAL '1 day'
				WHERE id = :id
				""").param("id", erased).update();
		signal(stranger, "EMAIL", "GOOGLE_EMAIL", IdentityTestSupport.fakeHmac("other@gmail.test"));

		String body = this.mvc.perform(get("/api/v1/admin/users/" + lan).with(admin(ha)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.personLinks.length()").value(2))
			.andExpect(jsonPath("$.personLinks[0].accountId").value(lan2.toString()))
			.andExpect(jsonPath("$.personLinks[0].name").value("Người dùng google-lan-2"))
			.andExpect(jsonPath("$.personLinks[0].kinds[0]").value("EMAIL"))
			.andExpect(jsonPath("$.personLinks[0].kinds[1]").value("LINKEDIN"))
			.andExpect(jsonPath("$.personLinks[1].accountId").value(erased.toString()))
			.andExpect(jsonPath("$.personLinks[1].name").doesNotExist())
			.andExpect(jsonPath("$.personLinks[1].kinds[0]").value("EMAIL"))
			.andReturn()
			.getResponse()
			.getContentAsString();
		assertThat(body).doesNotContain(email).doesNotContain(linkedin).doesNotContain(stranger.toString());
		assertThat(audits(lan)).isOne();

		// An account that shares nothing lists nobody, and nothing was read to audit.
		this.mvc.perform(get("/api/v1/admin/users/" + stranger).with(admin(ha)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.personLinks.length()").value(0));
		assertThat(audits(stranger)).isZero();
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

	private long audits(UUID account) {
		return this.jdbc.sql("""
				SELECT count(*) FROM audit.audit_entry e JOIN audit.audit_entry_object o ON o.entry_id = e.id
				WHERE e.action = 'identity.person_links_viewed' AND o.object_type = 'identity.account'
				  AND o.object_id = :account
				""").param("account", account).query(Long.class).single();
	}

	private RequestPostProcessor admin(Administrator administrator) {
		return IdentityTestSupport.signedIn(this.sessions, administrator.accountId(), Role.ADMINISTRATOR, this.clock.instant());
	}

}
