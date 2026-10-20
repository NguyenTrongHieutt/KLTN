package vn.vibefix.catalog.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.groups.Tuple.tuple;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import vn.vibefix.catalog.Catalog;
import vn.vibefix.catalog.Skill;
import vn.vibefix.catalog.SkillCategory;
import vn.vibefix.identity.internal.IdentityTestSupport;
import vn.vibefix.shared.ApiException;
import vn.vibefix.shared.Role;
import vn.vibefix.support.IntegrationTest;
import vn.vibefix.support.PostgresTestDatabase;

/**
 * FR-72, AD-38, SG-50: the seeded catalog, its API with an ETag, its unique names under the shared
 * folding, and the folding itself against the fixtures the SPA tests too.
 */
class CatalogIT extends IntegrationTest {

	static final Path FOLDING_FIXTURES = Path.of("..", "frontend", "src", "lib", "vi-normalize.fixtures.json");

	static final UUID REACT = UUID.fromString("0192b000-0000-7000-8001-000000000004");

	static final UUID TYPESCRIPT = UUID.fromString("0192b000-0000-7000-8001-000000000003");

	@Autowired
	private MockMvc mvc;

	@Autowired
	private Catalog catalog;

	@Autowired
	private JdbcClient jdbc;

	@Autowired
	private TransactionTemplate transactions;

	@Test
	void theSeedIsAddendumB7InDisplayOrder() {
		List<SkillCategory> categories = this.catalog.active();

		assertThat(categories).hasSize(8);
		assertThat(categories).extracting(SkillCategory::name)
			.startsWith("Giao diện web", "Máy chủ và API")
			.endsWith("Công cụ tạo ứng dụng bằng AI");
		assertThat(categories.stream().mapToInt(category -> category.skills().size()).sum()).isEqualTo(61);
		assertThat(categories.getFirst().skills()).extracting(Skill::name)
			.containsExactly("HTML & CSS", "JavaScript", "TypeScript", "React", "Next.js", "Vue.js", "Tailwind CSS", "Vite");
		assertThat(categories).flatExtracting(SkillCategory::skills)
			.allSatisfy(skill -> assertThat(skill.hint()).isNotBlank().doesNotEndWith(" "));
	}

	@Test
	void signedInCallersReadItWithAnETagAndRevalidate() throws Exception {
		UUID student = IdentityTestSupport.insertAccount(this.jdbc, Role.STUDENT, null, null);

		MockHttpServletResponse first = this.mvc
			.perform(get("/api/v1/catalog").with(authentication(IdentityTestSupport.authentication(student, Role.STUDENT))))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.categories.length()").value(8))
			.andExpect(jsonPath("$.categories[0].skills[3].name").value("React"))
			.andExpect(jsonPath("$.categories[0].skills[3].hint").isNotEmpty())
			.andReturn()
			.getResponse();
		String etag = first.getHeader("ETag");
		assertThat(etag).matches("\"[0-9a-f]{32}\"");
		assertThat(first.getHeaders("Cache-Control")).containsExactly("private, no-cache");

		this.mvc.perform(get("/api/v1/catalog").header("If-None-Match", etag)
			.with(authentication(IdentityTestSupport.authentication(student, Role.STUDENT))))
			.andExpect(status().isNotModified())
			.andExpect(header().string("ETag", etag));
		this.mvc.perform(get("/api/v1/catalog")).andExpect(status().isUnauthorized());
	}

	@Test
	void namesAreUniqueUnderTheFolding() throws SQLException {
		try (Connection connection = database.migratorConnection(PostgresTestDatabase.DATABASE);
				Statement statement = connection.createStatement()) {
			assertThatThrownBy(() -> statement.executeUpdate("""
					INSERT INTO catalog.skill (id, category_id, name, hint, display_order, created_at, updated_at)
					VALUES (gen_random_uuid(), '0192b000-0000-7000-8000-000000000001', 'react', 'x', 99, now(), now())
					""")).hasMessageContaining("skill_name_idx");
			assertThatThrownBy(() -> statement.executeUpdate("""
					INSERT INTO catalog.skill_category (id, name, display_order, created_at, updated_at)
					VALUES (gen_random_uuid(), 'Bao mat va kiem thu', 99, now(), now())
					""")).hasMessageContaining("skill_category_name_idx");
		}
	}

	/** FR-72: ADM-12 adds and edits through the runtime role (Story 10.8), but nothing is ever deleted. */
	@Test
	void theRuntimeRoleNeverDeletes() {
		assertThatThrownBy(() -> this.jdbc.sql("DELETE FROM catalog.skill WHERE id = :id")
			.param("id", REACT)
			.update()).rootCause().hasMessageContaining("permission denied");
		assertThatThrownBy(() -> this.jdbc.sql("DELETE FROM catalog.skill_category WHERE id = :id")
			.param("id", UUID.randomUUID())
			.update()).rootCause().hasMessageContaining("permission denied");
	}

	@Test
	void byIdsKeepsTheOrderIncludesInactiveSkillsAndDropsUnknownOnes() throws SQLException {
		deactivate(REACT);

		assertThat(this.catalog.byIds(List.of(REACT, UUID.randomUUID(), TYPESCRIPT)))
			.extracting(Skill::name, Skill::active)
			.containsExactly(tuple("React", false),
					tuple("TypeScript", true));
		assertThat(this.catalog.active().getFirst().skills()).extracting(Skill::name).doesNotContain("React");
	}

	@Test
	void requireActiveAndRequireAtMostRefuseWithTheirReasons() throws SQLException {
		deactivate(REACT);

		assertThatThrownBy(() -> this.catalog.requireActive(List.of(TYPESCRIPT, REACT)))
			.isInstanceOfSatisfying(ApiException.class, ex -> assertThat(ex.reason()).hasValue("SKILL_INACTIVE"));
		assertThatThrownBy(() -> this.catalog.requireActive(List.of(UUID.randomUUID())))
			.isInstanceOfSatisfying(ApiException.class, ex -> assertThat(ex.reason()).hasValue("SKILL_INACTIVE"));
		this.catalog.requireActive(List.of(TYPESCRIPT, TYPESCRIPT));
		assertThatThrownBy(() -> this.catalog.requireAtMost(21, 20))
			.isInstanceOfSatisfying(ApiException.class, ex -> {
				assertThat(ex.reason()).hasValue("SKILL_LIMIT");
				assertThat(ex.details()).containsEntry("limit", 20);
			});
		this.catalog.requireAtMost(20, 20);
	}

	/** SG-50: the database's folding is the SPA's, case by case, with an empty search_path too. */
	@Test
	void viNormalizeFoldsTheSharedFixtures() throws Exception {
		JsonNode fixtures = JsonMapper.builder().build().readTree(Files.readString(FOLDING_FIXTURES, StandardCharsets.UTF_8));
		assertThat(fixtures.size()).isGreaterThan(5);
		for (JsonNode fixture : fixtures) {
			String input = fixture.path("input").asString();
			String folded = this.transactions.execute(status -> {
				this.jdbc.sql("SET LOCAL search_path = ''").update();
				return this.jdbc.sql("SELECT platform.vi_normalize(:value)").param("value", input).query(String.class).single();
			});
			assertThat(folded).as("vi_normalize(%s)", input).isEqualTo(fixture.path("folded").asString());
		}
	}

	private static void deactivate(UUID skill) throws SQLException {
		try (Connection connection = database.migratorConnection(PostgresTestDatabase.DATABASE);
				Statement statement = connection.createStatement()) {
			statement.executeUpdate("UPDATE catalog.skill SET active = FALSE WHERE id = '" + skill + "'");
		}
	}

}
