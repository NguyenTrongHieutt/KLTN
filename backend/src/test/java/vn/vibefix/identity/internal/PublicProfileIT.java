package vn.vibefix.identity.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.session.Session;
import org.springframework.session.SessionRepository;
import org.springframework.test.web.servlet.MockMvc;

import vn.vibefix.support.IntegrationTest;
import vn.vibefix.support.MutableClock;
import vn.vibefix.team.internal.TeamTestSupport;

/**
 * PUB-4's identity half (FR-51, UX-DR96, AD-42, X9, Story 21.3), for anyone at {@code /api/v1/users/{username}}: the
 * Verified facts in their fixed order with "Chưa xác minh" for each one missing, the typed profile, the Skills by
 * Skill Category and the month joined; the student's details unless hidden; a locked person's page says only that they
 * are not active; never contact details or the school email; a username no linked account holds, or two hold, is not
 * found.
 */
class PublicProfileIT extends IntegrationTest {

	private static final LocalDate VERIFIED_UNTIL = LocalDate.parse("2026-09-30");

	@Autowired
	private MockMvc mvc;

	@Autowired
	private JdbcClient jdbc;

	@Autowired
	private SessionRepository<? extends Session> sessions;

	@Test
	void aPersonsProfileShowsTheVerifiedFactsInOrderTheTypedProfileAndTheSkills() throws Exception {
		UUID minh = TeamTestSupport.student(this.jdbc, "Trần Quang Minh", true,
				List.of(TeamTestSupport.REACT, TeamTestSupport.SUPABASE), "React, Supabase", VERIFIED_UNTIL);
		this.jdbc.sql("""
				UPDATE identity.profile SET display_name = 'Minh Trần', experience = 'Làm ứng dụng đặt bàn.',
				    years_of_experience = 3, contact_email = 'minh.lienhe@example.test', phone = '+84 966 123 456'
				WHERE account_id = :id
				""").param("id", minh).update();
		this.jdbc.sql("INSERT INTO identity.profile_link (account_id, position, url) VALUES (:id, 0, 'https://minh.dev')")
			.param("id", minh)
			.update();
		String login = TeamTestSupport.login(this.jdbc, minh);

		String body = this.mvc.perform(get("/api/v1/users/" + login.toUpperCase()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.locked").value(false))
			.andExpect(jsonPath("$.displayName").value("Minh Trần"))
			.andExpect(jsonPath("$.githubLogin").value(login))
			.andExpect(jsonPath("$.verified.length()").value(3))
			.andExpect(jsonPath("$.verified[0].kind").value("GITHUB"))
			.andExpect(jsonPath("$.verified[0].verifiedAt").value("2026-01-05T03:00:00Z"))
			.andExpect(jsonPath("$.verified[0].githubLogin").value(login))
			.andExpect(jsonPath("$.verified[1].kind").value("STUDENT"))
			.andExpect(jsonPath("$.verified[1].verifiedAt").isNotEmpty())
			.andExpect(jsonPath("$.verified[1].school").value(TeamTestSupport.SCHOOL))
			.andExpect(jsonPath("$.verified[1].name").value("Trần Quang Minh"))
			.andExpect(jsonPath("$.verified[1].major").value(TeamTestSupport.MAJOR))
			.andExpect(jsonPath("$.verified[2].kind").value("LINKEDIN"))
			.andExpect(jsonPath("$.verified[2].verifiedAt").value(Matchers.nullValue()))
			.andExpect(jsonPath("$.experience").value("Làm ứng dụng đặt bàn."))
			.andExpect(jsonPath("$.yearsOfExperience").value(3))
			.andExpect(jsonPath("$.links[0]").value("https://minh.dev"))
			.andExpect(jsonPath("$.skillGroups[0].category").value("Giao diện web"))
			.andExpect(jsonPath("$.skillGroups[0].skills[0]").value("React"))
			.andExpect(jsonPath("$.memberSince").value("2026-01-05T03:00:00Z"))
			.andExpect(jsonPath("$.own").value(false))
			.andReturn()
			.getResponse()
			.getContentAsString(StandardCharsets.UTF_8);
		assertThat(body).as("never contact details, the school email, or an account ID")
			.doesNotContain("minh.lienhe@example.test", "966 123 456", "@hust.edu.vn", minh.toString(), "hmac");

		// "Đây là hồ sơ người khác thấy": the owner's own page says so.
		this.mvc.perform(get("/api/v1/users/" + login)
			.with(IdentityTestSupport.signedIn(this.sessions, minh, Role.STUDENT, MutableClock.DEFAULT_INSTANT)))
			.andExpect(jsonPath("$.own").value(true));
		UUID other = IdentityTestSupport.insertAccount(this.jdbc, Role.CUSTOMER, null);
		this.mvc.perform(get("/api/v1/users/" + login)
			.with(IdentityTestSupport.signedIn(this.sessions, other, Role.CUSTOMER, MutableClock.DEFAULT_INSTANT)))
			.andExpect(jsonPath("$.own").value(false));
	}

	@Test
	void hiddenStudentFactsLeaveTheBadgeAndALapsedOneReadsUnverified() throws Exception {
		UUID hidden = TeamTestSupport.student(this.jdbc, "Nguyễn Ẩn Danh", false, List.of(TeamTestSupport.REACT), "React",
				VERIFIED_UNTIL);
		String login = TeamTestSupport.login(this.jdbc, hidden);

		String body = this.mvc.perform(get("/api/v1/users/" + login))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.displayName").value("@" + login))
			.andExpect(jsonPath("$.verified[1].kind").value("STUDENT"))
			.andExpect(jsonPath("$.verified[1].verifiedAt").isNotEmpty())
			.andExpect(jsonPath("$.verified[1].school").value(Matchers.nullValue()))
			.andExpect(jsonPath("$.verified[1].name").value(Matchers.nullValue()))
			.andExpect(jsonPath("$.verified[1].major").value(Matchers.nullValue()))
			.andReturn()
			.getResponse()
			.getContentAsString(StandardCharsets.UTF_8);
		assertThat(body).doesNotContain("Nguyễn Ẩn Danh", TeamTestSupport.SCHOOL, TeamTestSupport.MAJOR);

		// After its last valid day the student fact is "Chưa xác minh", though its Deadline has yet to end it.
		this.clock.setInstant(Instant.parse("2026-09-30T17:00:00Z"));
		this.mvc.perform(get("/api/v1/users/" + login))
			.andExpect(jsonPath("$.verified[1].kind").value("STUDENT"))
			.andExpect(jsonPath("$.verified[1].verifiedAt").value(Matchers.nullValue()));
	}

	@Test
	void aLockedPersonShowsOnlyThatTheyAreNotActive() throws Exception {
		UUID locked = TeamTestSupport.mentor(this.jdbc, "Người Bị Khóa", List.of(TeamTestSupport.REACT), "React");
		IdentityTestSupport.lock(this.jdbc, locked, "Vi phạm quy định");
		String login = TeamTestSupport.login(this.jdbc, locked);

		String body = this.mvc.perform(get("/api/v1/users/" + login))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.locked").value(true))
			.andExpect(jsonPath("$.displayName").value(Matchers.nullValue()))
			.andExpect(jsonPath("$.verified.length()").value(0))
			.andExpect(jsonPath("$.skillGroups.length()").value(0))
			.andReturn()
			.getResponse()
			.getContentAsString(StandardCharsets.UTF_8);
		assertThat(body).doesNotContain("Người Bị Khóa", "Vi phạm", "React");
		this.mvc.perform(get("/api/v1/users/" + login + "/reputation")).andExpect(status().isNotFound());
	}

	@Test
	void aUsernameNoLinkedAccountHoldsOrTwoHoldIsNotFound() throws Exception {
		this.mvc.perform(get("/api/v1/users/khong-co-ai")).andExpect(status().isNotFound());
		this.mvc.perform(get("/api/v1/users/khong-co-ai/reputation")).andExpect(status().isNotFound());

		// An unlinked account keeps no username (FR-80).
		UUID unlinked = TeamTestSupport.mentor(this.jdbc, "Chưa Liên Kết", List.of(TeamTestSupport.REACT), "React");
		String old = TeamTestSupport.login(this.jdbc, unlinked);
		IdentityTestSupport.unlinkGitHub(this.jdbc, unlinked);
		this.mvc.perform(get("/api/v1/users/" + old)).andExpect(status().isNotFound());

		// github_login has no unique index: an old username another account still holds finds no one.
		UUID first = TeamTestSupport.mentor(this.jdbc, "Người Thứ Nhất", List.of(TeamTestSupport.REACT), "React");
		UUID second = TeamTestSupport.mentor(this.jdbc, "Người Thứ Hai", List.of(TeamTestSupport.REACT), "React");
		String shared = TeamTestSupport.login(this.jdbc, first);
		this.jdbc.sql("UPDATE identity.account SET github_login = :login WHERE id = :id")
			.param("login", shared.toUpperCase())
			.param("id", second)
			.update();
		this.mvc.perform(get("/api/v1/users/" + shared)).andExpect(status().isNotFound());
		this.mvc.perform(get("/api/v1/users/" + shared + "/reputation")).andExpect(status().isNotFound());
	}

}
