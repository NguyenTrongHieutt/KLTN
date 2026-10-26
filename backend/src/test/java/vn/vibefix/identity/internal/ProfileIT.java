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
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import org.hamcrest.Matchers;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.session.Session;
import org.springframework.session.SessionRepository;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.support.TransactionTemplate;

import vn.vibefix.catalog.SkillRenamed;
import vn.vibefix.identity.ContactVisibility;
import vn.vibefix.identity.StudentDirectory;
import vn.vibefix.notification.RecipientAddresses;
import vn.vibefix.shared.Role;
import vn.vibefix.support.IntegrationTest;
import vn.vibefix.support.MutableClock;
import vn.vibefix.support.PostgresTestDatabase;

/**
 * Story 19.3 (FR-3, FR-4, FR-51, FR-72, AD-36, AD-38, AD-42): one profile per account, whatever role it
 * holds, with a typed zone that saves without approval and a verified zone no save changes; the contact
 * details, the notification address, the student-details switch, and Skill renames.
 */
class ProfileIT extends IntegrationTest {

	static final UUID TYPESCRIPT = skill(3);

	static final UUID REACT = skill(4);

	static final UUID DOCKER = UUID.fromString("0192b000-0000-7000-8001-000000000034");

	@Autowired
	private MockMvc mvc;

	@Autowired
	private JdbcClient jdbc;

	@Autowired
	private ContactVisibility contacts;

	@Autowired
	private RecipientAddresses addresses;

	@Autowired
	private StudentDirectory students;

	@Autowired
	private ApplicationEventPublisher events;

	@Autowired
	private TransactionTemplate transactions;

	@Autowired
	private SessionRepository<? extends Session> sessions;

	@Test
	void aFirstSaveCreatesTheRowWithItsSkillNamesAndIsAuditedForAnAccountWithNoRole() throws Exception {
		UUID lan = google("lan");
		this.mvc.perform(get("/api/v1/profile").with(as(lan)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.displayName").doesNotExist())
			.andExpect(jsonPath("$.kind").value("INDIVIDUAL"))
			.andExpect(jsonPath("$.skills").isEmpty())
			.andExpect(jsonPath("$.hideStudentDetails").value(false))
			.andExpect(jsonPath("$.studentDetails").doesNotExist());
		assertThat(profileRows(lan)).isZero();

		save(lan, body("Nguyễn Thị Lan", List.of(REACT, TYPESCRIPT, REACT),
				List.of("https://lan.dev", "https://lan.dev"), List.of("https://github.com/lan/shop", "Lan/Shop.git", "lan/blog"),
				false)).andExpect(status().isNoContent());

		this.mvc.perform(get("/api/v1/profile").with(as(lan)))
			.andExpect(jsonPath("$.displayName").value("Nguyễn Thị Lan"))
			.andExpect(jsonPath("$.kind").value("BUSINESS"))
			.andExpect(jsonPath("$.contactEmail").value("lan@tiembanh.vn"))
			.andExpect(jsonPath("$.phone").value("+84 912 345 678"))
			.andExpect(jsonPath("$.experience").value("Bán bánh 5 năm."))
			.andExpect(jsonPath("$.yearsOfExperience").value(5))
			.andExpect(jsonPath("$.skills[*].name").value(Matchers.contains("React", "TypeScript")))
			.andExpect(jsonPath("$.skills[0].active").value(true))
			.andExpect(jsonPath("$.links").value(Matchers.contains("https://lan.dev")))
			.andExpect(jsonPath("$.repositories").value(Matchers.contains("lan/shop", "lan/blog")));
		assertThat(text("SELECT skill_names FROM identity.profile WHERE account_id = '" + lan + "'"))
			.isEqualTo("React, TypeScript");
		assertThat(this.jdbc.sql("""
				SELECT count(*) FROM audit.audit_entry WHERE action = 'identity.profile_saved'
				""").query(Long.class).single()).isOne();
		assertThat(this.jdbc.sql("SELECT count(*) FROM identity.person_signal WHERE account_id = :id")
			.param("id", lan)
			.query(Long.class)
			.single()).as("a typed link records no signal").isZero();
	}

	@Test
	void anyRoleKeepsTheSameOneProfileAndAnAdministratorHasNone() throws Exception {
		for (Role role : new Role[] { Role.CUSTOMER, Role.MENTOR, Role.STUDENT }) {
			UUID account = IdentityTestSupport.insertAccount(this.jdbc, role, null, null);
			this.mvc.perform(put("/api/v1/profile").with(authentication(IdentityTestSupport.authentication(account, role)))
				.with(csrf())
				.contentType(MediaType.APPLICATION_JSON)
				.content(body("Người dùng", List.of(REACT), List.of(), List.of(), false))).andExpect(status().isNoContent());
			assertThat(profileRows(account)).as(role.name()).isOne();
		}
		UUID admin = IdentityTestSupport.insertAccount(this.jdbc, Role.ADMINISTRATOR, null, null);
		RequestPostProcessor administrator = IdentityTestSupport.signedIn(this.sessions, admin, Role.ADMINISTRATOR,
				this.clock.instant());
		this.mvc.perform(get("/api/v1/profile").with(administrator)).andExpect(status().isNotFound());
		this.mvc.perform(put("/api/v1/profile").with(administrator).with(csrf())
			.contentType(MediaType.APPLICATION_JSON)
			.content(body("Quản trị", List.of(), List.of(), List.of(), false))).andExpect(status().isNotFound());
	}

	@Test
	void typedLimitsAreRefusedAndNothingIsSaved() throws Exception {
		UUID lan = google("lan");
		setActive(DOCKER, false);

		save(lan, body("Lan", IntStream.rangeClosed(1, 21).mapToObj(ProfileIT::skill).toList(), List.of(), List.of(), false))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("VALIDATION"))
			.andExpect(jsonPath("$.reason").value("SKILL_LIMIT"))
			.andExpect(jsonPath("$.limit").value(20));
		save(lan, body("Lan", List.of(REACT, DOCKER), List.of(), List.of(), false)).andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("VALIDATION"))
			.andExpect(jsonPath("$.reason").value("SKILL_INACTIVE"));
		save(lan, body("Lan", List.of(), List.of("http://lan.dev"), List.of(), false)).andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("VALIDATION"))
			.andExpect(jsonPath("$.errors[0].field").value(Matchers.startsWith("links")));
		save(lan, body("Lan", List.of(), IntStream.rangeClosed(1, 6).mapToObj(n -> "https://lan.dev/" + n).toList(), List.of(),
				false)).andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("VALIDATION"))
			.andExpect(jsonPath("$.errors[0].field").value("links"));
		save(lan, body("Lan", List.of(), List.of(), IntStream.rangeClosed(1, 6).mapToObj(n -> "lan/app-" + n).toList(), false))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("VALIDATION"))
			.andExpect(jsonPath("$.errors[0].field").value("repositories"));
		save(lan, body("Lan", List.of(), List.of(), List.of("https://evil.example/a/b"), false))
			.andExpect(status().isBadRequest());
		save(lan, body(" ", List.of(), List.of(), List.of(), false)).andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.errors[0].field").value("displayName"));

		assertThat(profileRows(lan)).isZero();
		assertThat(this.jdbc.sql("""
				SELECT (SELECT count(*) FROM identity.profile_link) + (SELECT count(*) FROM identity.showcase_repository)
				""").query(Long.class).single()).isZero();
	}

	@Test
	void aSkillDeactivatedLaterIsKeptOnTheNextSave() throws Exception {
		UUID lan = google("lan");
		save(lan, body("Lan", List.of(REACT), List.of(), List.of(), false)).andExpect(status().isNoContent());
		setActive(REACT, false);

		save(lan, body("Lan", List.of(REACT, TYPESCRIPT), List.of(), List.of(), false)).andExpect(status().isNoContent());

		this.mvc.perform(get("/api/v1/profile").with(as(lan)))
			.andExpect(jsonPath("$.skills[0].name").value("React"))
			.andExpect(jsonPath("$.skills[0].active").value(false))
			.andExpect(jsonPath("$.skills[1].name").value("TypeScript"));
		UUID other = google("other");
		save(other, body("Khác", List.of(REACT), List.of(), List.of(), false)).andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.reason").value("SKILL_INACTIVE"));
	}

	@Test
	void theVerifiedZoneIsReadFromTheLinkAndTheCurrentVerificationAndNoSaveChangesIt() throws Exception {
		UUID minh = IdentityTestSupport.insertAccount(this.jdbc, Role.STUDENT, null, null);
		String login = text("SELECT github_login FROM identity.account WHERE id = '" + minh + "'");
		verify(minh, LocalDate.of(2026, 9, 30));

		this.mvc.perform(get("/api/v1/profile").with(authentication(IdentityTestSupport.authentication(minh, Role.STUDENT))))
			.andExpect(jsonPath("$.verified.github.login").value(login))
			.andExpect(jsonPath("$.verified.github.since").value(MutableClock.DEFAULT_INSTANT.toString()))
			.andExpect(jsonPath("$.verified.student.validUntil").value("2026-09-30"))
			.andExpect(jsonPath("$.verified.student.method").value("SCHOOL_EMAIL"))
			.andExpect(jsonPath("$.verified.linkedin").doesNotExist());

		// A save that tries to write the facts writes none of them.
		this.mvc.perform(put("/api/v1/profile").with(authentication(IdentityTestSupport.authentication(minh, Role.STUDENT)))
			.with(csrf())
			.contentType(MediaType.APPLICATION_JSON)
			.content("""
					{"displayName":"Minh","skillIds":[],"links":[],"repositories":[],"hideStudentDetails":false,
					 "githubLogin":"someone-else","verified":{"github":{"login":"someone-else"},
					 "student":{"validUntil":"2030-09-30","method":"SCHOOL_EMAIL"},"linkedin":{"since":"2026-01-01T00:00:00Z"}}}
					"""))
			.andExpect(status().isNoContent());
		this.mvc.perform(get("/api/v1/profile").with(authentication(IdentityTestSupport.authentication(minh, Role.STUDENT))))
			.andExpect(jsonPath("$.verified.github.login").value(login))
			.andExpect(jsonPath("$.verified.student.validUntil").value("2026-09-30"))
			.andExpect(jsonPath("$.verified.linkedin").doesNotExist());
		assertThat(text("SELECT github_login || ' ' || valid_until FROM identity.account a JOIN identity.student_verification v"
				+ " ON v.account_id = a.id WHERE a.id = '" + minh + "'")).isEqualTo(login + " 2026-09-30");

		// A lapsed verification, and an account with neither, show no fact.
		this.clock.setInstant(Instant.parse("2026-10-01T03:00:00Z"));
		this.mvc.perform(get("/api/v1/profile").with(authentication(IdentityTestSupport.authentication(minh, Role.STUDENT))))
			.andExpect(jsonPath("$.verified.student").doesNotExist());
		UUID lan = google("lan");
		this.mvc.perform(get("/api/v1/profile").with(as(lan)))
			.andExpect(jsonPath("$.verified.github").doesNotExist())
			.andExpect(jsonPath("$.verified.student").doesNotExist())
			.andExpect(jsonPath("$.verified.linkedin").doesNotExist());
	}

	@Test
	void hidingTheStudentDetailsHidesThemOnTheCardAndLeavesThemOutOfSearch() throws Exception {
		UUID minh = IdentityTestSupport.insertAccount(this.jdbc, Role.STUDENT, null, null);
		verify(minh, LocalDate.of(2026, 9, 30));
		this.jdbc.sql("""
				INSERT INTO identity.profile (account_id, display_name, student_name, student_school, student_year, updated_at)
				VALUES (:id, 'Minh', 'Trần Quang Minh', 'Đại học Bách khoa Hà Nội', 3, :now)
				""").param("id", minh).param("now", Timestamp.from(this.clock.instant())).update();

		assertThat(this.students.cards(List.of(minh))).singleElement().satisfies(card -> {
			assertThat(card.name()).isEqualTo("Trần Quang Minh");
			assertThat(card.school()).isEqualTo("Đại học Bách khoa Hà Nội");
		});
		assertThat(this.students.search("Bách khoa", List.of(), PageRequest.of(0, 10)).getContent())
			.extracting(StudentDirectory.StudentCard::accountId)
			.containsExactly(minh);
		this.mvc.perform(get("/api/v1/profile").with(authentication(IdentityTestSupport.authentication(minh, Role.STUDENT))))
			.andExpect(jsonPath("$.hideStudentDetails").value(false))
			.andExpect(jsonPath("$.studentDetails.name").value("Trần Quang Minh"))
			.andExpect(jsonPath("$.studentDetails.year").value(3));

		this.mvc.perform(put("/api/v1/profile").with(authentication(IdentityTestSupport.authentication(minh, Role.STUDENT)))
			.with(csrf())
			.contentType(MediaType.APPLICATION_JSON)
			.content(body("Minh", List.of(), List.of(), List.of(), true))).andExpect(status().isNoContent());

		assertThat(this.students.cards(List.of(minh))).singleElement().satisfies(card -> {
			assertThat(card.name()).isNull();
			assertThat(card.school()).isNull();
			assertThat(card.yearOfStudy()).isNull();
		});
		assertThat(this.students.search("Bách khoa", List.of(), PageRequest.of(0, 10)).getContent()).isEmpty();
		assertThat(this.jdbc.sql("""
				SELECT search_vector @@ plainto_tsquery('platform.vi_search', 'Bách khoa') FROM identity.profile
				WHERE account_id = :id
				""").param("id", minh).query(Boolean.class).single()).isFalse();
		// The save keeps the details as they were declared; only the switch changed.
		this.mvc.perform(get("/api/v1/profile").with(authentication(IdentityTestSupport.authentication(minh, Role.STUDENT))))
			.andExpect(jsonPath("$.hideStudentDetails").value(true))
			.andExpect(jsonPath("$.studentDetails.school").value("Đại học Bách khoa Hà Nội"));
	}

	@Test
	void theContactIsReadByTheOwnerAnAdministratorAndAGranteeWhateverItsRoleOnly() throws Exception {
		UUID lan = google("lan");
		UUID grantee = google("grantee");
		UUID crew = IdentityTestSupport.insertAccount(this.jdbc, Role.STUDENT, null, null);
		UUID other = IdentityTestSupport.insertAccount(this.jdbc, Role.MENTOR, null, null);
		UUID admin = IdentityTestSupport.insertAccount(this.jdbc, Role.ADMINISTRATOR, null, null);
		save(lan, body("Nguyễn Thị Lan", List.of(), List.of(), List.of(), false)).andExpect(status().isNoContent());
		String contact = "/api/v1/customers/" + lan + "/contact";

		this.mvc.perform(get(contact).with(as(lan)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.contactEmail").value("lan@tiembanh.vn"))
			.andExpect(jsonPath("$.phone").value("+84 912 345 678"));
		this.mvc.perform(get(contact).with(IdentityTestSupport.signedIn(this.sessions, admin, Role.ADMINISTRATOR,
				this.clock.instant()))).andExpect(status().isOk());
		this.mvc.perform(get(contact).with(as(grantee))).andExpect(status().isNotFound());

		UUID job = UUID.randomUUID();
		this.contacts.grant(lan, grantee, job);
		this.mvc.perform(get(contact).with(as(grantee))).andExpect(status().isOk())
			.andExpect(jsonPath("$.contactEmail").value("lan@tiembanh.vn"));
		this.mvc.perform(get(contact).with(authentication(IdentityTestSupport.authentication(crew, Role.STUDENT))))
			.andExpect(status().isNotFound());
		this.mvc.perform(get(contact).with(authentication(IdentityTestSupport.authentication(other, Role.MENTOR))))
			.andExpect(status().isNotFound());

		this.contacts.end(lan, grantee, job, this.clock.instant());
		this.mvc.perform(get(contact).with(as(grantee))).andExpect(status().isNotFound());
	}

	@Test
	void aSavedContactEmailTakesOverFromTheGoogleEmail() throws Exception {
		UUID lan = google("lan");
		assertThat(this.addresses.emailAddress(lan)).contains("lan@gmail.test");

		save(lan, body("Nguyễn Thị Lan", List.of(), List.of(), List.of(), false)).andExpect(status().isNoContent());

		assertThat(this.addresses.emailAddress(lan)).contains("lan@tiembanh.vn");
	}

	@Test
	void aRenameRewritesTheNamesOnceEvenWhenTheEventComesAgain() throws Exception {
		UUID lan = google("lan");
		save(lan, body("Lan", List.of(REACT, DOCKER), List.of(), List.of(), false)).andExpect(status().isNoContent());
		UUID eventId = UUID.randomUUID();

		rename(REACT, "React.js");
		publish(new SkillRenamed(eventId, REACT, "React.js"));
		await().atMost(Duration.ofSeconds(10)).until(() -> skillNames(lan).equals("React.js, Docker"));

		// A replay of the same event, after another change it does not describe, changes nothing.
		rename(REACT, "ReactJS");
		publish(new SkillRenamed(eventId, REACT, "React.js"));
		await().atMost(Duration.ofSeconds(10))
			.until(() -> this.jdbc.sql("SELECT count(*) FROM platform.event_publication WHERE completion_date IS NULL")
				.query(Long.class)
				.single() == 0);
		assertThat(skillNames(lan)).isEqualTo("React.js, Docker");
	}

	@Test
	void theThemeIsSavedToTheAccountAndDefaultsToLight() throws Exception {
		UUID lan = IdentityTestSupport.insertAccount(this.jdbc, Role.CUSTOMER, null, null);
		RequestPostProcessor customer = authentication(IdentityTestSupport.authentication(lan, Role.CUSTOMER));
		this.mvc.perform(get("/api/v1/session").with(customer)).andExpect(jsonPath("$.account.theme").value("LIGHT"));

		this.mvc.perform(put("/api/v1/account/theme").with(customer).with(csrf())
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"theme\":\"DARK\"}")).andExpect(status().isNoContent());

		this.mvc.perform(get("/api/v1/session").with(customer)).andExpect(jsonPath("$.account.theme").value("DARK"));
	}

	private ResultActions save(UUID account, String json) throws Exception {
		return this.mvc.perform(put("/api/v1/profile").with(as(account)).with(csrf())
			.contentType(MediaType.APPLICATION_JSON)
			.content(json));
	}

	/** A save with every typed field; the Skills, links, and repositories as given. */
	private static String body(String displayName, List<UUID> skillIds, List<String> links, List<String> repositories,
			boolean hide) {
		return """
				{"displayName":%s,"kind":"BUSINESS","contactEmail":"lan@tiembanh.vn","phone":"+84 912 345 678",
				 "experience":"Bán bánh 5 năm.","yearsOfExperience":5,"skillIds":%s,"links":%s,"repositories":%s,
				 "hideStudentDetails":%s}
				""".formatted(quoted(displayName), array(skillIds.stream().map(UUID::toString).toList()), array(links),
				array(repositories), hide);
	}

	private static String array(List<String> values) {
		return values.stream().map(ProfileIT::quoted).collect(Collectors.joining(",", "[", "]"));
	}

	private static String quoted(String value) {
		return "\"" + value.replace("\"", "\\\"") + "\"";
	}

	private UUID google(String sub) {
		return IdentityTestSupport.insertGoogleAccount(this.jdbc, sub, "3.0");
	}

	private static RequestPostProcessor as(UUID account) {
		return authentication(IdentityTestSupport.authentication(account, null));
	}

	private void verify(UUID account, LocalDate validUntil) {
		this.jdbc.sql("""
				INSERT INTO identity.student_verification (account_id, method, school_email, verified_at, valid_until)
				VALUES (:id, 'SCHOOL_EMAIL', :email, :now, :until)
				""")
			.param("id", account)
			.param("email", account.toString().substring(0, 8) + "@hust.edu.vn")
			.param("now", Timestamp.from(this.clock.instant()))
			.param("until", validUntil)
			.update();
	}

	private long profileRows(UUID account) {
		return this.jdbc.sql("SELECT count(*) FROM identity.profile WHERE account_id = :id")
			.param("id", account)
			.query(Long.class)
			.single();
	}

	private String skillNames(UUID account) {
		return text("SELECT skill_names FROM identity.profile WHERE account_id = '" + account + "'");
	}

	private @Nullable String text(String sql) {
		return this.jdbc.sql(sql).query(String.class).single();
	}

	private void publish(SkillRenamed event) {
		this.transactions.executeWithoutResult(status -> this.events.publishEvent(event));
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
