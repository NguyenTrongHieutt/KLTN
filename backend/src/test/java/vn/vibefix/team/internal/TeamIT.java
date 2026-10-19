package vn.vibefix.team.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static vn.vibefix.team.internal.TeamTestSupport.REACT;
import static vn.vibefix.team.internal.TeamTestSupport.SUPABASE;
import static vn.vibefix.team.internal.TeamTestSupport.WEB_SECURITY;

import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.session.Session;
import org.springframework.session.SessionRepository;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.support.TransactionTemplate;

import com.jayway.jsonpath.JsonPath;

import vn.vibefix.identity.AccountLocked;
import vn.vibefix.identity.MentorApprovalRevoked;
import vn.vibefix.identity.MentorApprovalStatus;
import vn.vibefix.identity.MentorApproved;
import vn.vibefix.identity.internal.IdentityTestSupport;
import vn.vibefix.shared.Role;
import vn.vibefix.support.IntegrationTest;
import vn.vibefix.timers.internal.DeadlinePoller;

/** FR-6, FR-14, AD-36, AD-37: Teams, the searches, invitations, join requests, lapses, and the release. */
class TeamIT extends IntegrationTest {

	private static final LocalDate VERIFIED_UNTIL = LocalDate.of(2026, 9, 30);

	@Autowired
	private MockMvc mvc;

	@Autowired
	private JdbcClient jdbc;

	@Autowired
	private SessionRepository<? extends Session> sessions;

	@Autowired
	private ApplicationEventPublisher events;

	@Autowired
	private TransactionTemplate transactions;

	@Autowired
	private DeadlinePoller poller;

	@Autowired
	private TeamStore store;

	private UUID hung;

	private UUID minh;

	@BeforeEach
	void anApprovedMentorWithATeamAndAVerifiedStudent() {
		this.hung = TeamTestSupport.mentor(this.jdbc, MentorApprovalStatus.APPROVED, "Đỗ Mạnh Hùng",
				List.of(REACT, SUPABASE), "React, Supabase");
		this.store.ensure(this.hung, this.clock.instant());
		this.minh = TeamTestSupport.student(this.jdbc, "Trần Quang Minh", true, List.of(REACT, WEB_SECURITY),
				"React, Bảo mật web (OWASP Top 10)", VERIFIED_UNTIL);
	}

	@Test
	void anApprovalCreatesOneTeamEvenWhenTheEventComesTwice() throws Exception {
		UUID thao = TeamTestSupport.mentor(this.jdbc, MentorApprovalStatus.APPROVED, "Nguyễn Phương Thảo",
				List.of(SUPABASE), "Supabase");
		publish(new MentorApproved(UUID.randomUUID(), thao));
		publish(new MentorApproved(UUID.randomUUID(), thao));

		await().atMost(Duration.ofSeconds(10)).until(() -> teams(thao) == 1 && settled());
		assertThat(teams(thao)).isOne();
		this.mvc.perform(get("/api/v1/mentor/team").with(as(thao, Role.MENTOR)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value("OPEN"))
			.andExpect(jsonPath("$.size").value(0))
			.andExpect(jsonPath("$.sizeLimit").value(10))
			.andExpect(jsonPath("$.invitesLeft").value(20));
	}

	@Test
	void theStudentSearchFindsVerifiedStudentsByShownFieldsAndSkillsOnly() throws Exception {
		UUID hidden = TeamTestSupport.student(this.jdbc, "Lê Ẩn Danh", false, List.of(REACT), "React", VERIFIED_UNTIL);
		UUID unverified = TeamTestSupport.student(this.jdbc, "Phạm Chưa Xác", true, List.of(REACT), "React", null);
		UUID locked = TeamTestSupport.student(this.jdbc, "Vũ Bị Khóa", true, List.of(REACT), "React", VERIFIED_UNTIL);
		IdentityTestSupport.lock(this.jdbc, locked, "test");

		String byName = search("q=quang", null).andExpect(status().isOk())
			.andExpect(jsonPath("$.total").value(1))
			.andExpect(jsonPath("$.items[0].student.accountId").value(this.minh.toString()))
			.andExpect(jsonPath("$.items[0].student.name").value("Trần Quang Minh"))
			.andExpect(jsonPath("$.items[0].student.verified").value(true))
			.andExpect(jsonPath("$.items[0].relation").value("NONE"))
			.andReturn()
			.getResponse()
			.getContentAsString(StandardCharsets.UTF_8);
		assertThat(byName).doesNotContain("@hust.edu.vn");
		search("q=bao ma", null).andExpect(jsonPath("$.total").value(1))
			.andExpect(jsonPath("$.items[0].student.accountId").value(this.minh.toString()));
		// A hidden name is not searchable.
		search("q=an danh", null).andExpect(jsonPath("$.total").value(0));
		String bySkill = search("skills=" + REACT, null).andExpect(jsonPath("$.total").value(2))
			.andReturn()
			.getResponse()
			.getContentAsString(StandardCharsets.UTF_8);
		assertThat(bySkill).contains(hidden.toString()).doesNotContain("Lê Ẩn Danh")
			.doesNotContain(unverified.toString())
			.doesNotContain(locked.toString());
		search("login=" + TeamTestSupport.login(this.jdbc, hidden).toUpperCase(), null)
			.andExpect(jsonPath("$.total").value(1))
			.andExpect(jsonPath("$.items[0].student.name").doesNotExist());
		search("login=" + TeamTestSupport.login(this.jdbc, unverified), null).andExpect(jsonPath("$.total").value(0));

		this.mvc.perform(get("/api/v1/mentor/team/students/" + this.minh).with(as(this.hung, Role.MENTOR)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.student.name").value("Trần Quang Minh"))
			.andExpect(jsonPath("$.relation").value("NONE"))
			.andExpect(jsonPath("$.teamMentor").doesNotExist());
		this.mvc.perform(get("/api/v1/mentor/team/students/" + unverified).with(as(this.hung, Role.MENTOR)))
			.andExpect(status().isNotFound());

		UUID pending = TeamTestSupport.mentor(this.jdbc, MentorApprovalStatus.PENDING, "Chờ Duyệt", List.of(REACT), "React");
		search("q=quang", pending).andExpect(status().isForbidden())
			.andExpect(jsonPath("$.reason").value("MENTOR_NOT_APPROVED"));
		this.mvc.perform(get("/api/v1/mentor/team/students?q=quang").with(as(this.minh, Role.STUDENT)))
			.andExpect(status().isNotFound());
	}

	@Test
	void anInvitationAcceptedMakesTheStudentAMemberAndCancelsTheirOtherRequests() throws Exception {
		UUID thao = TeamTestSupport.mentor(this.jdbc, MentorApprovalStatus.APPROVED, "Nguyễn Phương Thảo",
				List.of(SUPABASE), "Supabase");
		this.store.ensure(thao, this.clock.instant());
		UUID joinRequest = id(joinRequest(this.minh, thao).andExpect(status().isCreated()));

		UUID invitation = id(invite(this.hung, this.minh).andExpect(status().isCreated())
			.andExpect(jsonPath("$.kind").value("INVITATION")));
		assertThat(notificationParam(this.minh, TeamRequests.INVITATION_RECEIVED, "mentor")).isEqualTo("Đỗ Mạnh Hùng");
		assertThat(dueOf(invitation)).isEqualTo(this.clock.instant().plus(TeamRequests.LAPSE));
		invite(this.hung, this.minh).andExpect(status().isConflict()).andExpect(jsonPath("$.reason").value("REQUEST_PENDING"));
		search("q=quang", null).andExpect(jsonPath("$.items[0].relation").value("INVITED"));

		this.mvc.perform(get("/api/v1/team").with(as(this.minh, Role.STUDENT)))
			.andExpect(jsonPath("$.verified").value(true))
			.andExpect(jsonPath("$.team").doesNotExist())
			.andExpect(jsonPath("$.invitations[0].id").value(invitation.toString()))
			.andExpect(jsonPath("$.invitations[0].mentor.displayName").value("Đỗ Mạnh Hùng"))
			.andExpect(jsonPath("$.joinRequests[0].id").value(joinRequest.toString()));

		answer("/api/v1/team/invitations/" + invitation + "/accept", this.minh, Role.STUDENT).andExpect(status().isOk());

		assertThat(this.store.isMember(this.hung, this.minh)).isTrue();
		assertThat(requestStatus(joinRequest)).as("the other request is cancelled").isEqualTo("CANCELLED");
		assertThat(notificationParam(this.hung, TeamRequests.INVITATION_ANSWERED, "answer")).isEqualTo("chấp nhận");
		this.mvc.perform(get("/api/v1/mentor/team").with(as(this.hung, Role.MENTOR)))
			.andExpect(jsonPath("$.size").value(1))
			.andExpect(jsonPath("$.members[0].student.name").value("Trần Quang Minh"))
			.andExpect(jsonPath("$.invitations.length()").value(0));
		this.mvc.perform(get("/api/v1/team").with(as(this.minh, Role.STUDENT)))
			.andExpect(jsonPath("$.team.mentor.displayName").value("Đỗ Mạnh Hùng"))
			.andExpect(jsonPath("$.team.size").value(1))
			.andExpect(jsonPath("$.team.listed[0].you").value(true));
		answer("/api/v1/team/invitations/" + invitation + "/accept", this.minh, Role.STUDENT)
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.reason").value("REQUEST_CLOSED"));
		joinRequest(this.minh, thao).andExpect(status().is(422)).andExpect(jsonPath("$.reason").value("STUDENT_IN_TEAM"));
		assertThat(audited("team.member_joined")).isOne();
	}

	@Test
	void theMentorAnswersJoinRequestsAndOnlyTheirOwn() throws Exception {
		UUID vy = TeamTestSupport.student(this.jdbc, "Lê Thảo Vy", true, List.of(REACT), "React", VERIFIED_UNTIL);
		UUID first = id(joinRequest(this.minh, this.hung).andExpect(status().isCreated()));
		this.clock.advance(Duration.ofMinutes(1));
		UUID second = id(joinRequest(vy, this.hung).andExpect(status().isCreated()));
		assertThat(notificationParam(this.hung, TeamRequests.JOIN_REQUEST_RECEIVED, "name")).isIn("Trần Quang Minh",
				"Lê Thảo Vy");
		this.mvc.perform(get("/api/v1/mentor/team").with(as(this.hung, Role.MENTOR)))
			.andExpect(jsonPath("$.joinRequests.length()").value(2))
			.andExpect(jsonPath("$.joinRequests[0].student.name").value("Trần Quang Minh"));

		UUID other = TeamTestSupport.mentor(this.jdbc, MentorApprovalStatus.APPROVED, "Người Khác", List.of(REACT), "React");
		answer("/api/v1/mentor/team/join-requests/" + first + "/accept", other, Role.MENTOR).andExpect(status().isNotFound());
		// A join request is not the Student's to accept.
		answer("/api/v1/team/invitations/" + first + "/accept", this.minh, Role.STUDENT).andExpect(status().isNotFound());

		answer("/api/v1/mentor/team/join-requests/" + first + "/accept", this.hung, Role.MENTOR).andExpect(status().isOk());
		answer("/api/v1/mentor/team/join-requests/" + second + "/decline", this.hung, Role.MENTOR)
			.andExpect(status().isOk());

		assertThat(this.store.isMember(this.hung, this.minh)).isTrue();
		assertThat(this.store.isMember(this.hung, vy)).isFalse();
		assertThat(notificationParam(this.minh, TeamRequests.JOIN_REQUEST_ANSWERED, "answer")).isEqualTo("nhận bạn vào nhóm");
		assertThat(notificationParam(vy, TeamRequests.JOIN_REQUEST_ANSWERED, "answer"))
			.isEqualTo("từ chối yêu cầu xin vào nhóm của bạn");
	}

	@Test
	void theDailyLimitsCountInvitationsAndJoinRequests() throws Exception {
		for (int i = 0; i < TeamRequests.INVITES_PER_DAY; i++) {
			UUID student = TeamTestSupport.student(this.jdbc, null, false, List.of(REACT), "React", VERIFIED_UNTIL);
			invite(this.hung, student).andExpect(status().isCreated());
		}
		invite(this.hung, this.minh).andExpect(status().isTooManyRequests())
			.andExpect(jsonPath("$.reason").value("TEAM_INVITE_LIMIT"));

		for (int i = 0; i < TeamRequests.JOIN_REQUESTS_PER_DAY; i++) {
			UUID mentor = TeamTestSupport.mentor(this.jdbc, MentorApprovalStatus.APPROVED, "Người hướng dẫn " + i,
					List.of(REACT), "React");
			joinRequest(this.minh, mentor).andExpect(status().isCreated());
		}
		UUID sixth = TeamTestSupport.mentor(this.jdbc, MentorApprovalStatus.APPROVED, "Thứ sáu", List.of(REACT), "React");
		joinRequest(this.minh, sixth).andExpect(status().isTooManyRequests())
			.andExpect(jsonPath("$.reason").value("JOIN_REQUEST_LIMIT"));

		this.clock.advance(Duration.ofDays(1));
		// A new day in Vietnam.
		joinRequest(this.minh, sixth).andExpect(status().isCreated());
	}

	@Test
	void aFullTeamTakesNoOneMore() throws Exception {
		UUID request = id(joinRequest(this.minh, this.hung).andExpect(status().isCreated()));
		UUID team = this.store.byMentor(this.hung).orElseThrow().id();
		for (int i = 0; i < 10; i++) {
			UUID member = TeamTestSupport.student(this.jdbc, null, false, List.of(REACT), "React", VERIFIED_UNTIL);
			this.store.insertMembership(team, member, this.clock.instant());
		}

		answer("/api/v1/mentor/team/join-requests/" + request + "/accept", this.hung, Role.MENTOR)
			.andExpect(status().is(422))
			.andExpect(jsonPath("$.reason").value("TEAM_FULL"));
		UUID vy = TeamTestSupport.student(this.jdbc, "Lê Thảo Vy", true, List.of(REACT), "React", VERIFIED_UNTIL);
		invite(this.hung, vy).andExpect(status().is(422)).andExpect(jsonPath("$.reason").value("TEAM_FULL"));
		assertThat(requestStatus(request)).isEqualTo("PENDING");
	}

	@Test
	void twoTeamsAcceptingTheSameStudentAtOnceMakeOneMembership() throws Exception {
		UUID thao = TeamTestSupport.mentor(this.jdbc, MentorApprovalStatus.APPROVED, "Nguyễn Phương Thảo",
				List.of(SUPABASE), "Supabase");
		this.store.ensure(thao, this.clock.instant());
		UUID toHung = id(joinRequest(this.minh, this.hung).andExpect(status().isCreated()));
		UUID toThao = id(joinRequest(this.minh, thao).andExpect(status().isCreated()));
		RequestPostProcessor asHung = as(this.hung, Role.MENTOR);
		RequestPostProcessor asThao = as(thao, Role.MENTOR);

		CountDownLatch start = new CountDownLatch(1);
		ExecutorService pool = Executors.newFixedThreadPool(2);
		List<Future<Integer>> results = new ArrayList<>();
		for (Object[] pair : List.of(new Object[] { toHung, asHung }, new Object[] { toThao, asThao })) {
			results.add(pool.submit(() -> {
				start.await();
				return this.mvc.perform(post("/api/v1/mentor/team/join-requests/" + pair[0] + "/accept")
					.with((RequestPostProcessor) pair[1])
					.with(csrf())).andReturn().getResponse().getStatus();
			}));
		}
		start.countDown();
		List<Integer> statuses = new ArrayList<>();
		for (Future<Integer> result : results) {
			statuses.add(result.get());
		}
		pool.shutdown();

		// The loser is refused either way: STUDENT_IN_TEAM when both reached the insert, or
		// REQUEST_CLOSED when the winner cancelled the loser's request before the loser locked it.
		assertThat(statuses).containsOnlyOnce(200).hasSize(2).allMatch(code -> code == 200 || code == 422 || code == 409);
		assertThat(this.jdbc.sql("SELECT count(*) FROM team.membership WHERE student_account_id = :id AND left_at IS NULL")
			.param("id", this.minh).query(Long.class).single()).isOne();
		assertThat(this.jdbc.sql("SELECT count(*) FROM team.team_request WHERE student_account_id = :id AND status = 'PENDING'")
			.param("id", this.minh).query(Long.class).single()).as("the loser's request is cancelled").isZero();
	}

	@Test
	void anUnansweredRequestLapsesAfter14DaysAndAnAnsweredOneIsObsolete() throws Exception {
		UUID vy = TeamTestSupport.student(this.jdbc, "Lê Thảo Vy", true, List.of(REACT), "React", VERIFIED_UNTIL);
		UUID unanswered = id(invite(this.hung, this.minh).andExpect(status().isCreated()));
		UUID answered = id(invite(this.hung, vy).andExpect(status().isCreated()));
		answer("/api/v1/team/invitations/" + answered + "/decline", vy, Role.STUDENT).andExpect(status().isOk());

		this.clock.advance(TeamRequests.LAPSE.plusMinutes(1));
		this.poller.runDue();

		assertThat(requestStatus(unanswered)).isEqualTo("LAPSED");
		assertThat(deadlineOf(unanswered)).isEqualTo("FIRED");
		assertThat(deadlineOf(answered)).isEqualTo("OBSOLETE");
		assertThat(this.jdbc.sql("""
				SELECT params ->> 'answer' FROM notification.notification WHERE account_id = :id AND type = :type
				ORDER BY created_at
				""").param("id", this.hung).param("type", TeamRequests.INVITATION_ANSWERED).query(String.class).list())
			.containsExactly("từ chối", "không trả lời");
	}

	@Test
	void aRevokedMentorsTeamClosesAndReleasesItsStudents() throws Exception {
		UUID vy = TeamTestSupport.student(this.jdbc, "Lê Thảo Vy", true, List.of(REACT), "React", VERIFIED_UNTIL);
		UUID tung = TeamTestSupport.student(this.jdbc, "Phạm Hải Tùng", true, List.of(REACT), "React", VERIFIED_UNTIL);
		UUID team = this.store.byMentor(this.hung).orElseThrow().id();
		this.store.insertMembership(team, this.minh, this.clock.instant());
		this.store.insertMembership(team, vy, this.clock.instant());
		UUID pending = id(invite(this.hung, tung).andExpect(status().isCreated()));
		this.jdbc.sql("UPDATE identity.account SET mentor_approval_status = 'REVOKED' WHERE id = :id")
			.param("id", this.hung)
			.update();

		publish(new MentorApprovalRevoked(UUID.randomUUID(), this.hung));

		await().atMost(Duration.ofSeconds(10)).until(() -> this.store.sizeOf(this.hung) == 0 && settled());
		assertThat(this.store.byMentor(this.hung).orElseThrow().status()).isEqualTo(TeamStore.Status.CLOSED);
		assertThat(requestStatus(pending)).isEqualTo("CANCELLED");
		assertThat(notificationParam(this.minh, TeamMemberships.RELEASED, "mentor")).isEqualTo("Đỗ Mạnh Hùng");
		assertThat(notificationParam(vy, TeamMemberships.RELEASED, "mentor")).isEqualTo("Đỗ Mạnh Hùng");
		joinRequest(tung, this.hung).andExpect(status().is(422)).andExpect(jsonPath("$.reason").value("TEAM_CLOSED"));
		this.mvc.perform(get("/api/v1/mentors/" + TeamTestSupport.login(this.jdbc, this.hung)))
			.andExpect(jsonPath("$.available").value(false));
		this.mvc.perform(get("/api/v1/mentor/team").with(as(this.hung, Role.MENTOR)))
			.andExpect(jsonPath("$.status").value("CLOSED"));
	}

	@Test
	void aLockedStudentLeavesTheirTeam() throws Exception {
		UUID team = this.store.byMentor(this.hung).orElseThrow().id();
		this.store.insertMembership(team, this.minh, this.clock.instant());

		publish(new AccountLocked(UUID.randomUUID(), this.minh, Role.STUDENT));

		await().atMost(Duration.ofSeconds(10)).until(() -> !this.store.isMember(this.hung, this.minh) && settled());
		assertThat(this.jdbc.sql("SELECT left_reason FROM team.membership WHERE student_account_id = :id")
			.param("id", this.minh).query(String.class).single()).isEqualTo("RELEASED");
	}

	@Test
	void pub5ShowsTheProfileTheTeamAndTheStudentsPlace() throws Exception {
		UUID hidden = TeamTestSupport.student(this.jdbc, "Lê Ẩn Danh", false, List.of(REACT), "React", VERIFIED_UNTIL);
		UUID team = this.store.byMentor(this.hung).orElseThrow().id();
		this.store.insertMembership(team, this.minh, this.clock.instant());
		this.store.insertMembership(team, hidden, this.clock.instant());
		String login = TeamTestSupport.login(this.jdbc, this.hung);

		String visitor = this.mvc.perform(get("/api/v1/mentors/" + login.toUpperCase()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.displayName").value("Đỗ Mạnh Hùng"))
			.andExpect(jsonPath("$.yearsOfExperience").value(8))
			.andExpect(jsonPath("$.skillGroups[0].category").value("Giao diện web"))
			.andExpect(jsonPath("$.skillGroups[0].skills[0]").value("React"))
			.andExpect(jsonPath("$.available").value(true))
			.andExpect(jsonPath("$.teamSize").value(2))
			.andExpect(jsonPath("$.listedMembers.length()").value(1))
			.andExpect(jsonPath("$.listedMembers[0].name").value("Trần Quang Minh"))
			.andExpect(jsonPath("$.viewer").doesNotExist())
			.andReturn()
			.getResponse()
			.getContentAsString(StandardCharsets.UTF_8);
		assertThat(visitor).doesNotContain("Lê Ẩn Danh").doesNotContain("linkedin");

		UUID vy = TeamTestSupport.student(this.jdbc, "Lê Thảo Vy", true, List.of(REACT), "React", VERIFIED_UNTIL);
		joinRequest(vy, this.hung).andExpect(status().isCreated());
		this.mvc.perform(get("/api/v1/mentors/" + login).with(as(vy, Role.STUDENT)))
			.andExpect(jsonPath("$.viewer.relation").value("REQUESTED"))
			.andExpect(jsonPath("$.viewer.inTeam").value(false))
			.andExpect(jsonPath("$.viewer.verified").value(true))
			.andExpect(jsonPath("$.viewer.requestsLeft").value(4))
			.andExpect(jsonPath("$.viewer.pending.kind").value("JOIN_REQUEST"));

		UUID pending = TeamTestSupport.mentor(this.jdbc, MentorApprovalStatus.PENDING, "Chờ Duyệt", List.of(REACT), "React");
		this.mvc.perform(get("/api/v1/mentors/" + TeamTestSupport.login(this.jdbc, pending))).andExpect(status().isNotFound());
	}

	@Test
	void anUnverifiedStudentCannotSearchOrAsk() throws Exception {
		UUID unverified = TeamTestSupport.student(this.jdbc, "Phạm Chưa Xác", true, List.of(REACT), "React", null);

		this.mvc.perform(get("/api/v1/team/mentors?q=hung").with(as(unverified, Role.STUDENT)))
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.reason").value("MEMBER_NOT_VERIFIED"));
		joinRequest(unverified, this.hung).andExpect(status().is(422))
			.andExpect(jsonPath("$.reason").value("MEMBER_NOT_VERIFIED"));
		invite(this.hung, unverified).andExpect(status().is(422))
			.andExpect(jsonPath("$.reason").value("MEMBER_NOT_VERIFIED"));
		this.mvc.perform(get("/api/v1/team/mentors?q=manh hu").with(as(this.minh, Role.STUDENT)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.items[0].mentor.displayName").value("Đỗ Mạnh Hùng"))
			.andExpect(jsonPath("$.items[0].relation").value("NONE"))
			.andExpect(jsonPath("$.requestsLeft").value(5));
	}

	private ResultActions search(String query, UUID mentor) throws Exception {
		return this.mvc.perform(get("/api/v1/mentor/team/students?" + query)
			.with(as(mentor == null ? this.hung : mentor, Role.MENTOR)));
	}

	private ResultActions invite(UUID mentor, UUID student) throws Exception {
		return this.mvc.perform(post("/api/v1/mentor/team/invitations").with(as(mentor, Role.MENTOR))
			.with(csrf())
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"studentId\":\"" + student + "\"}"));
	}

	private ResultActions joinRequest(UUID student, UUID mentor) throws Exception {
		return this.mvc.perform(post("/api/v1/team/join-requests").with(as(student, Role.STUDENT))
			.with(csrf())
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"mentorId\":\"" + mentor + "\"}"));
	}

	private ResultActions answer(String path, UUID account, Role role) throws Exception {
		return this.mvc.perform(post(path).with(as(account, role)).with(csrf()));
	}

	private static UUID id(ResultActions result) throws Exception {
		return UUID.fromString(JsonPath.read(result.andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8),
				"$.id"));
	}

	private void publish(Object event) {
		this.transactions.executeWithoutResult(status -> this.events.publishEvent(event));
	}

	private boolean settled() {
		return this.jdbc.sql("SELECT count(*) FROM platform.event_publication WHERE completion_date IS NULL")
			.query(Long.class)
			.single() == 0;
	}

	private long teams(UUID mentor) {
		return this.jdbc.sql("SELECT count(*) FROM team.team WHERE mentor_account_id = :id")
			.param("id", mentor).query(Long.class).single();
	}

	private String requestStatus(UUID request) {
		return this.jdbc.sql("SELECT status FROM team.team_request WHERE id = :id")
			.param("id", request).query(String.class).single();
	}

	private String deadlineOf(UUID request) {
		return this.jdbc.sql("SELECT status FROM timers.deadline WHERE subject_id = :id")
			.param("id", request).query(String.class).single();
	}

	private java.time.Instant dueOf(UUID request) {
		return this.jdbc.sql("SELECT due_at FROM timers.deadline WHERE subject_id = :id")
			.param("id", request).query(Timestamp.class).single().toInstant();
	}

	private String notificationParam(UUID account, String type, String param) {
		return this.jdbc.sql("""
				SELECT params ->> :param FROM notification.notification WHERE account_id = :id AND type = :type
				ORDER BY created_at DESC LIMIT 1
				""").param("param", param).param("id", account).param("type", type).query(String.class).single();
	}

	private long audited(String action) {
		return this.jdbc.sql("SELECT count(*) FROM audit.audit_entry WHERE action = :action")
			.param("action", action).query(Long.class).single();
	}

	private RequestPostProcessor as(UUID account, Role role) {
		return IdentityTestSupport.signedIn(this.sessions, account, role, this.clock.instant());
	}

}
