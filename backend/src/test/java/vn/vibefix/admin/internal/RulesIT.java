package vn.vibefix.admin.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.ArrayList;
import java.util.List;

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
import vn.vibefix.settings.Rule;
import vn.vibefix.settings.Settings;
import vn.vibefix.shared.Role;
import vn.vibefix.support.IntegrationTest;

/**
 * ADM-10 (FR-61, FR-63, SG-10): values outside their bounds are refused before the step-up; a saved
 * change applies live, replaces the school allowlist, records each old and new value, and is audited;
 * the Team Issue values stay hidden until Epic 12.
 */
class RulesIT extends IntegrationTest {

	@Autowired
	private MockMvc mvc;

	@Autowired
	private JdbcClient jdbc;

	@Autowired
	private SessionRepository<? extends Session> sessions;

	@Autowired
	private ApplicationContext context;

	@Autowired
	private Settings settings;

	@Test
	void aSavedChangeAppliesLiveAndRecordsEachOldAndNewValue() throws Exception {
		Administrator ha = AdministratorTestSupport.create(this.context, "ha.phuong@vibefix.test", "Hà Phương");
		this.mvc.perform(get("/api/v1/admin/rules").with(admin(ha))).andExpect(status().isOk())
			.andExpect(jsonPath("$.rules[?(@.key == 'AUTO_ACCEPTANCE_DAYS')].value").value(7))
			.andExpect(jsonPath("$.rules[?(@.key == 'MENTOR_SHARE_CAP_PERCENT')].min").value(10))
			.andExpect(jsonPath("$.rules[?(@.key == 'TEAM_ISSUE_ANSWER_DAYS')].value").value(5));

		save(ha, "{\"AUTO_ACCEPTANCE_DAYS\":31}", List.of(), "000000").andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.reason").value("RULE_OUT_OF_BOUNDS"));
		save(ha, "{}", List.of("không phải tên miền"), "000000").andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.reason").value("DOMAIN_INVALID"));

		List<String> domains = new ArrayList<>(this.settings.schoolDomains());
		String removed = domains.isEmpty() ? null : domains.removeFirst();
		domains.add("Truong-Moi.edu.vn");
		save(ha, "{\"AUTO_ACCEPTANCE_DAYS\":10,\"MENTOR_ACTIVE_JOB_LIMIT\":4,\"RATING_WINDOW_DAYS\":14}", domains,
				AdministratorTestSupport.code(ha, this.clock.instant()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.schoolDomains[?(@ == 'truong-moi.edu.vn')]").isNotEmpty());
		assertThat(this.settings.value(Rule.AUTO_ACCEPTANCE_DAYS)).isEqualTo(10);
		assertThat(this.settings.value(Rule.MENTOR_ACTIVE_JOB_LIMIT)).isEqualTo(4);
		assertThat(this.settings.allowsSchoolEmail("sv@sis.truong-moi.edu.vn")).isTrue();
		assertThat(this.jdbc.sql("""
				SELECT old_value || '>' || new_value FROM settings.change WHERE rule_key = 'AUTO_ACCEPTANCE_DAYS'
				""").query(String.class).single()).isEqualTo("7>10");
		assertThat(count("SELECT count(*) FROM settings.change WHERE kind = 'RULE'")).isEqualTo(2);
		assertThat(count("SELECT count(*) FROM settings.change WHERE kind = 'DOMAIN_ADDED'")).isEqualTo(1);
		if (removed != null) {
			assertThat(this.settings.schoolDomains()).doesNotContain(removed);
			assertThat(count("SELECT count(*) FROM settings.change WHERE kind = 'DOMAIN_REMOVED'")).isEqualTo(1);
		}
		assertThat(count("SELECT count(*) FROM audit.audit_entry WHERE action = 'admin.rules_changed'")).isEqualTo(1);
		this.mvc.perform(get("/api/v1/admin/rules").with(admin(ha)))
			.andExpect(jsonPath("$.history[?(@.rule == 'AUTO_ACCEPTANCE_DAYS')].newValue").value(10));
	}

	private ResultActions save(Administrator administrator, String values, List<String> domains, String code)
			throws Exception {
		String list = String.join(",", domains.stream().map(domain -> "\"" + domain + "\"").toList());
		return this.mvc.perform(put("/api/v1/admin/rules").with(admin(administrator)).with(csrf())
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"values\":" + values + ",\"schoolDomains\":[" + list + "],\"stepUpCode\":\"" + code + "\"}"));
	}

	private long count(String sql) {
		return this.jdbc.sql(sql).query(Long.class).single();
	}

	private RequestPostProcessor admin(Administrator administrator) {
		return IdentityTestSupport.signedIn(this.sessions, administrator.accountId(), Role.ADMINISTRATOR, this.clock.instant());
	}

}
