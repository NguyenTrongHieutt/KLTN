package vn.vibefix.admin.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.session.Session;
import org.springframework.session.SessionRepository;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import vn.vibefix.catalog.Catalog;
import vn.vibefix.identity.MentorApprovalStatus;
import vn.vibefix.identity.internal.AdministratorTestSupport;
import vn.vibefix.identity.internal.AdministratorTestSupport.Administrator;
import vn.vibefix.identity.internal.IdentityTestSupport;
import vn.vibefix.shared.Role;
import vn.vibefix.support.IntegrationTest;
import vn.vibefix.team.internal.TeamTestSupport;

/**
 * ADM-12 (FR-72, FR-63, AD-38): a Skill is added with a unique name ignoring accents and case, renamed so
 * profiles follow, taken out of pickers and back, and Categories are added, renamed, reordered, and
 * deactivated only without active Skills. Each change keeps its old and new values and is audited.
 */
class CatalogAdminIT extends IntegrationTest {

	private static final String WEB = "0192b000-0000-7000-8000-000000000001";

	@Autowired
	private MockMvc mvc;

	@Autowired
	private JdbcClient jdbc;

	@Autowired
	private SessionRepository<? extends Session> sessions;

	@Autowired
	private ApplicationContext context;

	@Autowired
	private Catalog catalog;

	@Test
	void skillsAreAddedRenamedAndRetiredWithTheirHistory() throws Exception {
		Administrator ha = AdministratorTestSupport.create(this.context, "ha.phuong@vibefix.test", "Hà Phương");
		UUID hung = TeamTestSupport.mentor(this.jdbc, MentorApprovalStatus.APPROVED, "Đỗ Mạnh Hùng",
				List.of(TeamTestSupport.REACT), "React");
		UUID vy = TeamTestSupport.student(this.jdbc, "Trần Thảo Vy", true, List.of(TeamTestSupport.REACT), "React",
				LocalDate.parse("2026-09-30"));

		send(ha, post("/api/v1/admin/catalog/skills"), skill("Svelte", "Giao diện viết bằng Svelte")).andExpect(status().isCreated())
			.andExpect(jsonPath("$.items[?(@.name == 'Svelte')]").isNotEmpty());
		send(ha, post("/api/v1/admin/catalog/skills"), skill("svelte", "Trùng tên")).andExpect(status().isConflict())
			.andExpect(jsonPath("$.reason").value("SKILL_NAME_TAKEN"));
		this.mvc.perform(get("/api/v1/admin/catalog/skills").param("q", "SVÉLTE").with(admin(ha)))
			.andExpect(jsonPath("$.total").value(1));

		send(ha, put("/api/v1/admin/catalog/skills/" + TeamTestSupport.REACT),
				skill("React.js", "Thư viện giao diện React")).andExpect(status().isOk());
		await().atMost(Duration.ofSeconds(10)).until(() -> text("SELECT skill_names FROM identity.profile WHERE account_id = '"
				+ hung + "'").contains("React.js"));
		await().atMost(Duration.ofSeconds(10)).until(() -> text("SELECT skill_names FROM identity.profile WHERE account_id = '"
				+ vy + "'").contains("React.js"));
		assertThat(text("""
				SELECT (old_value ->> 'name') || '>' || (new_value ->> 'name') FROM catalog.change WHERE kind = 'SKILL_UPDATED'
				""")).isEqualTo("React>React.js");

		send(ha, post("/api/v1/admin/catalog/skills/" + TeamTestSupport.REACT + "/active"), "{\"active\":false}")
			.andExpect(status().isOk());
		assertThat(this.catalog.active().stream().flatMap(category -> category.skills().stream()).map(skill -> skill.id().toString())
			.collect(Collectors.toSet())).doesNotContain(TeamTestSupport.REACT);
		send(ha, post("/api/v1/admin/catalog/skills/" + TeamTestSupport.REACT + "/active"), "{\"active\":true}")
			.andExpect(status().isOk());
		assertThat(count("SELECT count(*) FROM audit.audit_entry WHERE action LIKE 'admin.skill_%'")).isEqualTo(4);
		awaitListeners();
	}

	@Test
	void categoriesAreAddedRenamedReorderedAndDeactivatedOnlyWhenEmpty() throws Exception {
		Administrator ha = AdministratorTestSupport.create(this.context, "ha.phuong@vibefix.test", "Hà Phương");
		send(ha, post("/api/v1/admin/catalog/categories"), "{\"name\":\"Trí tuệ nhân tạo\"}").andExpect(status().isCreated());
		send(ha, post("/api/v1/admin/catalog/categories"), "{\"name\":\"tri tue nhan tao\"}").andExpect(status().isConflict())
			.andExpect(jsonPath("$.reason").value("CATEGORY_NAME_TAKEN"));
		String created = text("SELECT id::text FROM catalog.skill_category WHERE name = 'Trí tuệ nhân tạo'");
		send(ha, put("/api/v1/admin/catalog/categories/" + created), "{\"name\":\"Học máy\"}").andExpect(status().isOk());

		List<String> order = this.jdbc.sql("SELECT id::text FROM catalog.skill_category ORDER BY display_order DESC")
			.query(String.class)
			.list();
		String ids = order.stream().map(id -> "\"" + id + "\"").collect(Collectors.joining(","));
		send(ha, put("/api/v1/admin/catalog/categories/order"), "{\"ids\":[" + ids + "]}").andExpect(status().isOk())
			.andExpect(jsonPath("$[0].id").value(order.getFirst()));
		send(ha, put("/api/v1/admin/catalog/categories/order"), "{\"ids\":[\"" + created + "\"]}")
			.andExpect(status().isBadRequest());

		send(ha, post("/api/v1/admin/catalog/categories/" + WEB + "/active"), "{\"active\":false}")
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.reason").value("CATEGORY_HAS_ACTIVE_SKILLS"));
		send(ha, post("/api/v1/admin/catalog/categories/" + created + "/active"), "{\"active\":false}")
			.andExpect(status().isOk())
			.andExpect(jsonPath("$[?(@.name == 'Học máy')].active").value(false));
	}

	private String skill(String name, String hint) {
		return "{\"categoryId\":\"" + WEB + "\",\"name\":\"" + name + "\",\"hint\":\"" + hint
				+ "\",\"matchLanguages\":[\"JavaScript\"],\"matchTools\":[\"LOVABLE\"]}";
	}

	private ResultActions send(Administrator administrator, MockHttpServletRequestBuilder request, String body)
			throws Exception {
		return this.mvc.perform(request.with(admin(administrator)).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(body));
	}

	private void awaitListeners() {
		await().atMost(Duration.ofSeconds(10))
			.until(() -> count("SELECT count(*) FROM platform.event_publication WHERE completion_date IS NULL") == 0);
	}

	private long count(String sql) {
		return this.jdbc.sql(sql).query(Long.class).single();
	}

	private String text(String sql) {
		return this.jdbc.sql(sql).query(String.class).single();
	}

	private RequestPostProcessor admin(Administrator administrator) {
		return IdentityTestSupport.signedIn(this.sessions, administrator.accountId(), Role.ADMINISTRATOR, this.clock.instant());
	}

}
