package vn.vibefix.admin.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
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
 * Story 16.4 (FR-73, FR-72, AD-34, SG-50): people suggest Skills the catalog lacks, at most 5 a day; a name an
 * active Skill has is refused with that Skill, one an inactive Skill has is flagged; on ADM-12 an Administrator
 * approves a suggestion into a Skill through the catalog command, or declines it with a reason, and the suggester
 * is told; each reads only their own suggestions.
 */
class SkillSuggestionIT extends IntegrationTest {

	@Autowired
	private MockMvc mvc;

	@Autowired
	private JdbcClient jdbc;

	@Autowired
	private SessionRepository<? extends Session> sessions;

	@Autowired
	private ApplicationContext context;

	private Administrator ha;

	private UUID vy;

	private UUID lan;

	@BeforeEach
	void twoPeopleAndAnAdministrator() {
		this.ha = AdministratorTestSupport.create(this.context, "ha.phuong@vibefix.test", "Hà Phương");
		this.vy = IdentityTestSupport.insertAccount(this.jdbc, Role.STUDENT, null, null);
		this.lan = IdentityTestSupport.insertAccount(this.jdbc, Role.CUSTOMER, null, null);
	}

	@Test
	void aSuggestionIsCheckedAgainstTheCatalogQueuedAndApprovedIntoASkill() throws Exception {
		suggest(this.vy, Role.STUDENT, "react").andExpect(status().isConflict())
			.andExpect(jsonPath("$.reason").value("SKILL_ALREADY_EXISTS"))
			.andExpect(jsonPath("$.skillName").value("React"));
		String inactive = this.jdbc.sql("""
				UPDATE catalog.skill SET active = false WHERE id = (SELECT id FROM catalog.skill WHERE name = 'Base44') RETURNING name
				""").query(String.class).single();
		suggest(this.vy, Role.STUDENT, inactive.toUpperCase()).andExpect(status().isCreated());
		suggest(this.vy, Role.STUDENT, "Supabase Edge Functions").andExpect(status().isCreated())
			.andExpect(jsonPath("$.status").value("OPEN"));
		suggest(this.ha.accountId(), Role.ADMINISTRATOR, "Hệ thống thiết kế").andExpect(status().isNotFound());

		this.mvc.perform(get("/api/v1/admin/overview").with(admin()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.skillSuggestions.count").value(2));
		this.mvc.perform(get("/api/v1/admin/skill-suggestions").with(admin()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$[?(@.name == '" + inactive.toUpperCase() + "')].inactiveSkill.name").value(inactive))
			.andExpect(jsonPath("$[?(@.name == 'Supabase Edge Functions')].inactiveSkill").value(org.hamcrest.Matchers.contains((Object) null)))
			.andExpect(jsonPath("$[?(@.name == 'Supabase Edge Functions')].suggesterRole").value("STUDENT"));
		UUID edge = this.jdbc.sql("SELECT id FROM admin.skill_suggestion WHERE name = 'Supabase Edge Functions'")
			.query(UUID.class)
			.single();
		UUID category = this.jdbc.sql("SELECT category_id FROM catalog.skill WHERE name = 'React'").query(UUID.class).single();
		decide(edge, "approve", "{\"categoryId\":\"" + category + "\",\"name\":\"Supabase Edge Functions\",\"hint\":\"\"}")
			.andExpect(status().isBadRequest());
		decide(edge, "approve", "{\"categoryId\":\"" + category
				+ "\",\"name\":\"Supabase Edge Functions\",\"hint\":\"Hàm chạy phía máy chủ của Supabase\"}")
			.andExpect(status().isOk())
			.andExpect(jsonPath("$[?(@.name == 'Supabase Edge Functions')].status").value("APPROVED"));
		decide(edge, "decline", "{\"reason\":\"Trùng\"}").andExpect(status().isConflict())
			.andExpect(jsonPath("$.reason").value("ALREADY_DECIDED"));

		assertThat(count("SELECT count(*) FROM catalog.skill WHERE name = 'Supabase Edge Functions' AND active")).isOne();
		assertThat(count("SELECT count(*) FROM audit.audit_entry WHERE action = 'admin.skill_created'")).isOne();
		assertThat(count("SELECT count(*) FROM notification.notification WHERE type = 'admin.skill_suggestion_approved' AND account_id = '"
				+ this.vy + "'")).isOne();
		UUID created = this.jdbc.sql("SELECT id FROM catalog.skill WHERE name = 'Supabase Edge Functions'").query(UUID.class).single();
		assertThat(count("SELECT count(*) FROM identity.student_profile WHERE '" + created + "' = ANY (skill_ids)")
				+ count("SELECT count(*) FROM job.job WHERE '" + created + "' = ANY (skill_ids)")).as("added to no profile or Job Post")
			.isZero();
	}

	@Test
	void aDeclineCarriesItsReasonTheDailyLimitHoldsAndEachReadsTheirOwn() throws Exception {
		suggest(this.lan, Role.CUSTOMER, "Thanh toán MoMo").andExpect(status().isCreated());
		UUID momo = this.jdbc.sql("SELECT id FROM admin.skill_suggestion WHERE name = 'Thanh toán MoMo'").query(UUID.class).single();
		decide(momo, "decline", "{\"reason\":\"Đã có kỹ năng Tích hợp thanh toán\"}").andExpect(status().isOk());
		assertThat(count("SELECT count(*) FROM notification.notification WHERE type = 'admin.skill_suggestion_declined' AND account_id = '"
				+ this.lan + "'")).isOne();

		for (int i = 1; i < SuggestedSkills.DAILY_LIMIT; i++) {
			suggest(this.lan, Role.CUSTOMER, "Kỹ năng thử " + i).andExpect(status().isCreated());
		}
		suggest(this.lan, Role.CUSTOMER, "Kỹ năng thứ sáu").andExpect(status().isTooManyRequests())
			.andExpect(jsonPath("$.reason").value("SKILL_SUGGESTION_LIMIT"))
			.andExpect(header().exists("Retry-After"));

		this.mvc.perform(get("/api/v1/skill-suggestions").with(as(this.lan, Role.CUSTOMER)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.length()").value(SuggestedSkills.DAILY_LIMIT))
			.andExpect(jsonPath("$[?(@.name == 'Thanh toán MoMo')].declineReason").value("Đã có kỹ năng Tích hợp thanh toán"));
		this.mvc.perform(get("/api/v1/skill-suggestions").with(as(this.vy, Role.STUDENT)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.length()").value(0));
		this.mvc.perform(get("/api/v1/skill-suggestions").with(admin())).andExpect(status().isNotFound());
	}

	private ResultActions suggest(UUID account, Role role, String name) throws Exception {
		return this.mvc.perform(post("/api/v1/skill-suggestions").with(as(account, role)).with(csrf())
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"name\":\"" + name + "\",\"note\":\"Dự án của tôi cần\"}"));
	}

	private ResultActions decide(UUID suggestion, String action, String body) throws Exception {
		return this.mvc.perform(post("/api/v1/admin/skill-suggestions/" + suggestion + "/" + action).with(admin()).with(csrf())
			.contentType(MediaType.APPLICATION_JSON)
			.content(body));
	}

	private long count(String sql) {
		return this.jdbc.sql(sql).query(Long.class).single();
	}

	private RequestPostProcessor admin() {
		return IdentityTestSupport.signedIn(this.sessions, this.ha.accountId(), Role.ADMINISTRATOR, this.clock.instant());
	}

	private RequestPostProcessor as(UUID account, Role role) {
		return IdentityTestSupport.signedIn(this.sessions, account, role, this.clock.instant());
	}

}
