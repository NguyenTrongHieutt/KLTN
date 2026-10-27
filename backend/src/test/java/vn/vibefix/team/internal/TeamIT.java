package vn.vibefix.team.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
import static vn.vibefix.team.internal.TeamTestSupport.REACT;
import static vn.vibefix.team.internal.TeamTestSupport.SUPABASE;
import static vn.vibefix.team.internal.TeamTestSupport.WEB_SECURITY;

import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.session.Session;
import org.springframework.session.SessionRepository;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.support.TransactionTemplate;

import vn.vibefix.identity.AccountLocked;
import vn.vibefix.identity.internal.IdentityTestSupport;
import vn.vibefix.identity.internal.Role;
import vn.vibefix.shared.ApiException;
import vn.vibefix.support.IntegrationTest;
import vn.vibefix.team.internal.TeamStore.Kind;
import vn.vibefix.timers.internal.DeadlinePoller;

/**
 * FR-6, AD-37, Story 20.3: no Team can be formed since Story 19.7, and no account role tells a Mentor or a Student
 * apart, so every Team endpoint answers nothing to every signed-in caller until Story 21.7 deletes them. What the
 * module still does runs here: a lock runs both of {@link TeamLifecycle}'s consequences, each acting only on what
 * the account holds, and a pending request lapses after 14 days.
 */
class TeamIT extends IntegrationTest {

	private static final LocalDate VERIFIED_UNTIL = LocalDate.of(2026, 9, 30);

	/** One request to a Team endpoint, built afresh for each caller. */
	private record Call(HttpMethod method, String path, @Nullable String body) {

		MockHttpServletRequestBuilder build() {
			MockHttpServletRequestBuilder built = request(this.method, this.path);
			return this.body == null ? built : built.contentType(MediaType.APPLICATION_JSON).content(this.body);
		}

	}

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

	@Autowired
	private TeamRequests requests;

	private UUID hung;

	private UUID minh;

	@BeforeEach
	void aMentorWithATeamAndAVerifiedStudent() {
		this.hung = TeamTestSupport.mentor(this.jdbc, "Đỗ Mạnh Hùng", List.of(REACT, SUPABASE), "React, Supabase");
		this.store.ensure(this.hung, this.clock.instant());
		this.minh = TeamTestSupport.student(this.jdbc, "Trần Quang Minh", true, List.of(REACT, WEB_SECURITY),
				"React, Bảo mật web (OWASP Top 10)", VERIFIED_UNTIL);
	}

	@Test
	void everyTeamEndpointFindsNothingForEveryCaller() throws Exception {
		UUID team = this.store.byMentor(this.hung).orElseThrow().id();
		this.store.insertMembership(team, this.minh, this.clock.instant());
		UUID membership = this.store.activeMembership(this.minh).orElseThrow().membershipId();
		UUID vy = TeamTestSupport.student(this.jdbc, "Lê Thảo Vy", true, List.of(REACT), "React", VERIFIED_UNTIL);
		UUID request = this.requests.invite(this.hung, vy).id();
		UUID lan = IdentityTestSupport.insertGoogleAccount(this.jdbc, "google-lan", "3.1");
		IdentityTestSupport.linkGitHub(this.jdbc, lan);
		List<Call> calls = List.of(new Call(HttpMethod.GET, "/api/v1/mentor/team", null),
				new Call(HttpMethod.GET, "/api/v1/mentor/team/students?q=quang", null),
				new Call(HttpMethod.GET, "/api/v1/mentor/team/students/" + this.minh, null),
				new Call(HttpMethod.POST, "/api/v1/mentor/team/invitations", "{\"studentId\":\"" + vy + "\"}"),
				new Call(HttpMethod.POST, "/api/v1/mentor/team/join-requests/" + request + "/accept", null),
				new Call(HttpMethod.POST, "/api/v1/mentor/team/join-requests/" + request + "/decline", null),
				new Call(HttpMethod.DELETE, "/api/v1/mentor/team/members/" + membership, null),
				new Call(HttpMethod.GET, "/api/v1/team", null), new Call(HttpMethod.GET, "/api/v1/team/mentors?q=hung", null),
				new Call(HttpMethod.POST, "/api/v1/team/join-requests", "{\"mentorId\":\"" + this.hung + "\"}"),
				new Call(HttpMethod.POST, "/api/v1/team/invitations/" + request + "/accept", null),
				new Call(HttpMethod.POST, "/api/v1/team/invitations/" + request + "/decline", null),
				new Call(HttpMethod.DELETE, "/api/v1/team/membership", null));
		List<UUID> callers = List.of(this.hung, this.minh, vy, lan);
		List<Role> roles = java.util.Arrays.asList(Role.MENTOR, Role.STUDENT, Role.STUDENT, null);
		for (int i = 0; i < callers.size(); i++) {
			for (Call call : calls) {
				int status = this.mvc.perform(call.build().with(as(callers.get(i), roles.get(i))).with(csrf()))
					.andReturn()
					.getResponse()
					.getStatus();
				assertThat(status).as(call.method() + " " + call.path() + " as " + roles.get(i)).isEqualTo(404);
			}
		}

		assertThat(this.store.isMember(this.hung, this.minh)).as("nothing changed").isTrue();
		assertThat(requestStatus(request)).isEqualTo("PENDING");
	}

	@Test
	void aLockedMentorsTeamClosesAndReleasesItsStudents() throws Exception {
		UUID vy = TeamTestSupport.student(this.jdbc, "Lê Thảo Vy", true, List.of(REACT), "React", VERIFIED_UNTIL);
		UUID tung = TeamTestSupport.student(this.jdbc, "Phạm Hải Tùng", true, List.of(REACT), "React", VERIFIED_UNTIL);
		UUID team = this.store.byMentor(this.hung).orElseThrow().id();
		this.store.insertMembership(team, this.minh, this.clock.instant());
		this.store.insertMembership(team, vy, this.clock.instant());
		UUID pending = this.requests.invite(this.hung, tung).id();
		IdentityTestSupport.lock(this.jdbc, this.hung, "Vi phạm");

		publish(new AccountLocked(UUID.randomUUID(), this.hung));

		await().atMost(Duration.ofSeconds(10)).until(() -> this.store.sizeOf(this.hung) == 0 && settled());
		assertThat(this.store.byMentor(this.hung).orElseThrow().status()).isEqualTo(TeamStore.Status.CLOSED);
		assertThat(requestStatus(pending)).isEqualTo("CANCELLED");
		assertThat(notificationParam(this.minh, TeamMemberships.RELEASED, "mentor")).isEqualTo("Đỗ Mạnh Hùng");
		assertThat(notificationParam(vy, TeamMemberships.RELEASED, "mentor")).isEqualTo("Đỗ Mạnh Hùng");
		assertThatThrownBy(() -> this.requests.requestToJoin(tung, this.hung)).isInstanceOf(ApiException.class)
			.satisfies(ex -> assertThat(((ApiException) ex).reason()).contains("TEAM_CLOSED"));
	}

	@Test
	void aLockedStudentLeavesTheirTeam() throws Exception {
		UUID team = this.store.byMentor(this.hung).orElseThrow().id();
		this.store.insertMembership(team, this.minh, this.clock.instant());

		publish(new AccountLocked(UUID.randomUUID(), this.minh));

		await().atMost(Duration.ofSeconds(10)).until(() -> !this.store.isMember(this.hung, this.minh) && settled());
		assertThat(this.jdbc.sql("SELECT left_reason FROM team.membership WHERE student_account_id = :id")
			.param("id", this.minh).query(String.class).single()).isEqualTo("RELEASED");
	}

	/** Story 20.3: no role switch decides which consequence runs; an account with no Team changes nothing. */
	@Test
	void everyLockRunsBothConsequencesWhateverRoleTheRowStores() throws Exception {
		UUID lan = IdentityTestSupport.insertGoogleAccount(this.jdbc, "google-lan", "3.1");
		UUID lansTeam = this.store.ensure(lan, this.clock.instant()).id();
		UUID vy = TeamTestSupport.student(this.jdbc, "Lê Thảo Vy", true, List.of(REACT), "React", VERIFIED_UNTIL);
		this.store.insertMembership(lansTeam, vy, this.clock.instant());
		UUID team = this.store.byMentor(this.hung).orElseThrow().id();
		this.store.insertMembership(team, this.minh, this.clock.instant());
		UUID loner = IdentityTestSupport.insertGoogleAccount(this.jdbc, "google-mot-minh", "3.1");

		publish(new AccountLocked(UUID.randomUUID(), lan));
		publish(new AccountLocked(UUID.randomUUID(), loner));

		await().atMost(Duration.ofSeconds(10)).until(() -> this.store.sizeOf(lan) == 0 && settled());
		assertThat(this.store.byMentor(lan).orElseThrow().status()).as("a role-less account's Team closes too")
			.isEqualTo(TeamStore.Status.CLOSED);
		assertThat(this.store.isMember(this.hung, this.minh)).as("another Team is untouched").isTrue();
	}

	@Test
	void anUnansweredRequestLapsesAfter14DaysAndAnAnsweredOneIsObsolete() throws Exception {
		UUID vy = TeamTestSupport.student(this.jdbc, "Lê Thảo Vy", true, List.of(REACT), "React", VERIFIED_UNTIL);
		UUID unanswered = this.requests.invite(this.hung, this.minh).id();
		UUID answered = this.requests.invite(this.hung, vy).id();
		this.requests.decline(vy, answered, Kind.INVITATION);

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

	private void publish(Object event) {
		this.transactions.executeWithoutResult(status -> this.events.publishEvent(event));
	}

	private boolean settled() {
		return this.jdbc.sql("SELECT count(*) FROM platform.event_publication WHERE completion_date IS NULL")
			.query(Long.class)
			.single() == 0;
	}

	private String requestStatus(UUID request) {
		return this.jdbc.sql("SELECT status FROM team.team_request WHERE id = :id")
			.param("id", request).query(String.class).single();
	}

	private String deadlineOf(UUID request) {
		return this.jdbc.sql("SELECT status FROM timers.deadline WHERE subject_id = :id")
			.param("id", request).query(String.class).single();
	}

	private String notificationParam(UUID account, String type, String param) {
		return this.jdbc.sql("""
				SELECT params ->> :param FROM notification.notification WHERE account_id = :id AND type = :type
				ORDER BY created_at DESC LIMIT 1
				""").param("param", param).param("id", account).param("type", type).query(String.class).single();
	}

	private RequestPostProcessor as(UUID account, @Nullable Role role) {
		return IdentityTestSupport.signedIn(this.sessions, account, role, this.clock.instant());
	}

}
