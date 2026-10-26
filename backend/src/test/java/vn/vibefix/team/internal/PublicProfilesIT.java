package vn.vibefix.team.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;

import vn.vibefix.identity.MentorApprovalStatus;
import vn.vibefix.identity.internal.IdentityTestSupport;
import vn.vibefix.shared.Role;
import vn.vibefix.support.IntegrationTest;

/** FR-51, FR-14, AD-33: PUB-4 and PUB-5's Ratings and Portfolio Entries, for anyone. */
class PublicProfilesIT extends IntegrationTest {

	private static final LocalDate VERIFIED_UNTIL = LocalDate.parse("2026-09-30");

	@Autowired
	private MockMvc mvc;

	@Autowired
	private JdbcClient jdbc;

	@Test
	void aStudentWhoHidesTheirNameShowsTheGitHubUsernameAndNoSchool() throws Exception {
		UUID hidden = TeamTestSupport.student(this.jdbc, "Nguyễn Ẩn Danh", false, List.of(TeamTestSupport.REACT,
				TeamTestSupport.SUPABASE), "React, Supabase", VERIFIED_UNTIL);
		String login = TeamTestSupport.login(this.jdbc, hidden);

		String body = this.mvc.perform(get("/api/v1/students/" + login.toUpperCase()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.githubLogin").value(login))
			.andExpect(jsonPath("$.name").value(org.hamcrest.Matchers.nullValue()))
			.andExpect(jsonPath("$.school").value(org.hamcrest.Matchers.nullValue()))
			.andExpect(jsonPath("$.verified").value(true))
			.andExpect(jsonPath("$.skillGroups[0].category").value("Giao diện web"))
			.andExpect(jsonPath("$.skillGroups[0].skills[0]").value("React"))
			.andExpect(jsonPath("$.skillGroups[1].skills[0]").value("Supabase"))
			.andExpect(jsonPath("$.team").value(org.hamcrest.Matchers.nullValue()))
			.andReturn()
			.getResponse()
			.getContentAsString();
		assertThat(body).doesNotContain("Nguyễn Ẩn Danh", "Bách khoa", "@hust.edu.vn", hidden.toString());
	}

	@Test
	void aStudentWhoShowsTheirNameShowsTheSchoolAndTheirTeam() throws Exception {
		UUID shown = TeamTestSupport.student(this.jdbc, "Trần Quang Minh", true, List.of(TeamTestSupport.REACT), "React",
				null);
		UUID mentor = TeamTestSupport.mentor(this.jdbc, MentorApprovalStatus.APPROVED, "Đỗ Mạnh Hùng",
				List.of(TeamTestSupport.REACT), "React");
		UUID team = UUID.randomUUID();
		this.jdbc.sql("INSERT INTO team.team (id, mentor_account_id, status, created_at) VALUES (:id, :mentor, 'OPEN', now())")
			.param("id", team)
			.param("mentor", mentor)
			.update();
		this.jdbc.sql("""
				INSERT INTO team.membership (id, team_id, student_account_id, joined_at) VALUES (:id, :team, :student, now())
				""").param("id", UUID.randomUUID()).param("team", team).param("student", shown).update();
		// The listed Student's own contact details (FR-3, AD-20): never on PUB-4 or in the Team.
		this.jdbc.sql("""
				UPDATE identity.profile SET contact_email = 'minh.lienhe@example.test', phone = '+84 966 123 456'
				WHERE account_id = :id
				""").param("id", shown).update();
		String team1 = this.mvc.perform(get("/api/v1/mentor/team")
			.with(authentication(IdentityTestSupport.authentication(mentor, Role.MENTOR))))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.members[0].student.name").value("Trần Quang Minh"))
			.andReturn()
			.getResponse()
			.getContentAsString();
		String student = this.mvc.perform(get("/api/v1/students/" + TeamTestSupport.login(this.jdbc, shown)))
			.andReturn()
			.getResponse()
			.getContentAsString();
		for (String response : List.of(team1, student)) {
			assertThat(response).doesNotContain("minh.lienhe@example.test").doesNotContain("966 123 456");
		}

		this.mvc.perform(get("/api/v1/students/" + TeamTestSupport.login(this.jdbc, shown)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.name").value("Trần Quang Minh"))
			.andExpect(jsonPath("$.school").value("Đại học Bách khoa Hà Nội"))
			.andExpect(jsonPath("$.yearOfStudy").value(3))
			.andExpect(jsonPath("$.verified").value(false))
			.andExpect(jsonPath("$.team.mentorDisplayName").value("Đỗ Mạnh Hùng"))
			.andExpect(jsonPath("$.team.mentorLogin").value(TeamTestSupport.login(this.jdbc, mentor)))
			.andExpect(jsonPath("$.team.size").value(1));
		this.mvc.perform(get("/api/v1/students/" + TeamTestSupport.login(this.jdbc, shown) + "/reputation"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.count").value(0))
			.andExpect(jsonPath("$.average").value(org.hamcrest.Matchers.nullValue()))
			.andExpect(jsonPath("$.portfolio.length()").value(0));
	}

	@Test
	void aLockedStudentOrAMentorHasNoStudentProfile() throws Exception {
		UUID locked = TeamTestSupport.student(this.jdbc, "Bị khóa", true, List.of(), "", VERIFIED_UNTIL);
		IdentityTestSupport.lock(this.jdbc, locked, "Vi phạm quy định");
		UUID mentor = TeamTestSupport.mentor(this.jdbc, MentorApprovalStatus.APPROVED, "Người hướng dẫn",
				List.of(TeamTestSupport.REACT), "React");

		this.mvc.perform(get("/api/v1/students/" + TeamTestSupport.login(this.jdbc, locked))).andExpect(status().isNotFound());
		this.mvc.perform(get("/api/v1/students/" + TeamTestSupport.login(this.jdbc, mentor))).andExpect(status().isNotFound());
		this.mvc.perform(get("/api/v1/students/" + TeamTestSupport.login(this.jdbc, locked) + "/reputation"))
			.andExpect(status().isNotFound());
	}

	@Test
	void aMentorWithNoClosedJobIsNewAndARevokedMentorKeepsTheirReputation() throws Exception {
		UUID approved = TeamTestSupport.mentor(this.jdbc, MentorApprovalStatus.APPROVED, "Mới vào",
				List.of(TeamTestSupport.REACT), "React");
		UUID revoked = TeamTestSupport.mentor(this.jdbc, MentorApprovalStatus.REVOKED, "Đã thu hồi",
				List.of(TeamTestSupport.REACT), "React");
		UUID pending = TeamTestSupport.mentor(this.jdbc, MentorApprovalStatus.PENDING, "Chờ duyệt",
				List.of(TeamTestSupport.REACT), "React");

		this.mvc.perform(get("/api/v1/mentors/" + TeamTestSupport.login(this.jdbc, approved) + "/reputation"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.newcomer").value(true))
			.andExpect(jsonPath("$.count").value(0))
			.andExpect(jsonPath("$.portfolio.length()").value(0));
		this.mvc.perform(get("/api/v1/mentors/" + TeamTestSupport.login(this.jdbc, revoked) + "/reputation"))
			.andExpect(status().isOk());
		this.mvc.perform(get("/api/v1/mentors/" + TeamTestSupport.login(this.jdbc, revoked)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.available").value(false));
		this.mvc.perform(get("/api/v1/mentors/" + TeamTestSupport.login(this.jdbc, pending) + "/reputation"))
			.andExpect(status().isNotFound());
	}

}
