package vn.vibefix.identity.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.support.TransactionTemplate;

import vn.vibefix.catalog.SkillRenamed;
import vn.vibefix.shared.Role;
import vn.vibefix.support.IntegrationTest;
import vn.vibefix.support.PostgresTestDatabase;

/** FR-4, AD-38: a Student's Skills are catalog IDs, checked on save and renamed by the catalog's event. */
class StudentSkillsIT extends IntegrationTest {

	static final UUID TYPESCRIPT = skill(3);

	static final UUID REACT = skill(4);

	static final UUID DOCKER = UUID.fromString("0192b000-0000-7000-8001-000000000034");

	@Autowired
	private MockMvc mvc;

	@Autowired
	private JdbcClient jdbc;

	@Autowired
	private ApplicationEventPublisher events;

	@Autowired
	private TransactionTemplate transactions;

	private UUID minh;

	@BeforeEach
	void aStudent() {
		this.minh = IdentityTestSupport.insertAccount(this.jdbc, Role.STUDENT, null, null);
	}

	@Test
	void skillsAreSavedOnceEachInOrderWithTheirNamesForSearch() throws Exception {
		save(List.of(REACT, TYPESCRIPT, REACT, DOCKER)).andExpect(status().isNoContent());

		this.mvc.perform(get("/api/v1/profile").with(student()))
			.andExpect(jsonPath("$.student.skills[*].name").value(Matchers.contains("React", "TypeScript", "Docker")))
			.andExpect(jsonPath("$.student.skills[0].active").value(true));
		assertThat(skillNames()).isEqualTo("React, TypeScript, Docker");
		assertThat(this.jdbc.sql("SELECT cardinality(skill_ids) FROM identity.student_profile WHERE account_id = :id")
			.param("id", this.minh)
			.query(Integer.class)
			.single()).isEqualTo(3);
	}

	@Test
	void moreThanTwentyAreRefused() throws Exception {
		List<UUID> many = IntStream.rangeClosed(1, 21).mapToObj(StudentSkillsIT::skill).toList();

		save(many).andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("VALIDATION"))
			.andExpect(jsonPath("$.reason").value("SKILL_LIMIT"))
			.andExpect(jsonPath("$.limit").value(20));
		save(many.subList(0, 20)).andExpect(status().isNoContent());
	}

	@Test
	void anInactiveSkillMayStayButNotBeAdded() throws Exception {
		save(List.of(REACT)).andExpect(status().isNoContent());
		setActive(REACT, false);

		save(List.of(REACT, TYPESCRIPT)).andExpect(status().isNoContent());
		this.mvc.perform(get("/api/v1/profile").with(student()))
			.andExpect(jsonPath("$.student.skills[0].name").value("React"))
			.andExpect(jsonPath("$.student.skills[0].active").value(false));

		UUID other = IdentityTestSupport.insertAccount(this.jdbc, Role.STUDENT, null, null);
		this.mvc.perform(put("/api/v1/profile/student").with(as(other)).with(csrf())
			.contentType(MediaType.APPLICATION_JSON)
			.content(body(List.of(REACT))))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.reason").value("SKILL_INACTIVE"));
		save(List.of(REACT, UUID.randomUUID())).andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.reason").value("SKILL_INACTIVE"));
	}

	@Test
	void aRenameRewritesTheNamesOnceEvenWhenTheEventComesAgain() throws Exception {
		save(List.of(REACT, DOCKER)).andExpect(status().isNoContent());
		UUID eventId = UUID.randomUUID();

		rename(REACT, "React.js");
		publish(new SkillRenamed(eventId, REACT, "React.js"));
		await().atMost(Duration.ofSeconds(10)).until(() -> skillNames().equals("React.js, Docker"));

		// A replay of the same event, after another change it does not describe, changes nothing.
		rename(REACT, "ReactJS");
		publish(new SkillRenamed(eventId, REACT, "React.js"));
		await().atMost(Duration.ofSeconds(10))
			.until(() -> this.jdbc.sql("SELECT count(*) FROM platform.event_publication WHERE completion_date IS NULL")
				.query(Long.class)
				.single() == 0);
		assertThat(skillNames()).isEqualTo("React.js, Docker");
	}

	private ResultActions save(List<UUID> skillIds) throws Exception {
		return this.mvc.perform(put("/api/v1/profile/student").with(student()).with(csrf())
			.contentType(MediaType.APPLICATION_JSON)
			.content(body(skillIds)));
	}

	private static String body(List<UUID> skillIds) {
		return "{\"showNameAndSchool\":false,\"repositories\":[],\"skillIds\":["
				+ skillIds.stream().map(id -> "\"" + id + "\"").collect(Collectors.joining(",")) + "]}";
	}

	private String skillNames() {
		return this.jdbc.sql("SELECT skill_names FROM identity.student_profile WHERE account_id = :id")
			.param("id", this.minh)
			.query(String.class)
			.single();
	}

	private void publish(SkillRenamed event) {
		this.transactions.executeWithoutResult(status -> this.events.publishEvent(event));
	}

	private RequestPostProcessor student() {
		return as(this.minh);
	}

	private static RequestPostProcessor as(UUID account) {
		return authentication(IdentityTestSupport.authentication(account, Role.STUDENT));
	}

	private static UUID skill(int number) {
		return UUID.fromString("0192b000-0000-7000-8001-%012d".formatted(number));
	}

	private static void rename(UUID skill, String name) throws SQLException {
		migrator("UPDATE catalog.skill SET name = '" + name + "' WHERE id = '" + skill + "'");
	}

	private static void setActive(UUID skill, boolean active) throws SQLException {
		migrator("UPDATE catalog.skill SET active = " + active + " WHERE id = '" + skill + "'");
	}

	private static void migrator(String sql) throws SQLException {
		try (Connection connection = database.migratorConnection(PostgresTestDatabase.DATABASE);
				Statement statement = connection.createStatement()) {
			statement.executeUpdate(sql);
		}
	}

}
