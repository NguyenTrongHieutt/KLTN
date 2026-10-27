package vn.vibefix.team.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static vn.vibefix.team.internal.TeamTestSupport.REACT;

import java.time.Duration;
import java.time.LocalDate;
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
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.support.TransactionTemplate;

import vn.vibefix.identity.AccountLocked;
import vn.vibefix.identity.internal.IdentityTestSupport;
import vn.vibefix.shared.ApiException;
import vn.vibefix.shared.Role;
import vn.vibefix.support.IntegrationTest;
import vn.vibefix.team.Teams;

/** FR-6, AD-37: leaving, removal, Crew reservations, and the guard every membership end applies. */
class TeamMembershipIT extends IntegrationTest {

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
	private TeamStore store;

	@Autowired
	private Teams teams;

	private UUID hung;

	private UUID minh;

	private UUID membership;

	@BeforeEach
	void aMentorWithOneMember() {
		this.hung = TeamTestSupport.mentor(this.jdbc, "Đỗ Mạnh Hùng", List.of(REACT), "React");
		UUID team = this.store.ensure(this.hung, this.clock.instant()).id();
		this.minh = TeamTestSupport.student(this.jdbc, "Trần Quang Minh", true, List.of(REACT), "React", VERIFIED_UNTIL);
		this.store.insertMembership(team, this.minh, this.clock.instant());
		this.membership = this.store.activeMembership(this.minh).orElseThrow().membershipId();
	}

	@Test
	void aStudentLeavesAndMayJoinAnotherTeam() throws Exception {
		leave(this.minh).andExpect(status().isNoContent());

		assertThat(this.teams.isMember(this.hung, this.minh)).isFalse();
		assertThat(leftReason()).isEqualTo("LEFT");
		assertThat(notificationParam(this.hung, TeamMemberships.MEMBER_LEFT, "name")).isEqualTo("Trần Quang Minh");
		leave(this.minh).andExpect(status().isNotFound());
		UUID thao = TeamTestSupport.mentor(this.jdbc, "Nguyễn Phương Thảo", List.of(REACT),
				"React");
		this.mvc.perform(post("/api/v1/team/join-requests").with(as(this.minh, Role.STUDENT)).with(csrf())
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"mentorId\":\"" + thao + "\"}")).andExpect(status().isCreated());
	}

	@Test
	void theMentorRemovesOnlyTheirOwnMembers() throws Exception {
		UUID other = TeamTestSupport.mentor(this.jdbc, "Người Khác", List.of(REACT), "React");
		this.store.ensure(other, this.clock.instant());
		remove(other, this.membership).andExpect(status().isNotFound());

		remove(this.hung, this.membership).andExpect(status().isNoContent());

		assertThat(leftReason()).isEqualTo("REMOVED");
		assertThat(notificationParam(this.minh, TeamMemberships.MEMBER_REMOVED, "mentor")).isEqualTo("Đỗ Mạnh Hùng");
		remove(this.hung, this.membership).andExpect(status().isNotFound());
	}

	@Test
	void aReservedMemberCanNeitherLeaveNorBeRemovedUntilTheReservationEnds() throws Exception {
		UUID crewRow = UUID.randomUUID();
		reserve(crewRow, this.hung, this.minh);
		reserve(crewRow, this.hung, this.minh);
		assertThat(this.jdbc.sql("SELECT count(*) FROM team.crew_reservation").query(Long.class).single())
			.as("reserving a Crew row again changes nothing").isOne();

		leave(this.minh).andExpect(status().is(422)).andExpect(jsonPath("$.reason").value("STUDENT_ON_CREW"));
		remove(this.hung, this.membership).andExpect(status().is(422))
			.andExpect(jsonPath("$.reason").value("STUDENT_ON_CREW"));
		this.mvc.perform(get("/api/v1/mentor/team").with(as(this.hung, Role.MENTOR)))
			.andExpect(jsonPath("$.members[0].onCrew").value(true));
		this.mvc.perform(get("/api/v1/team").with(as(this.minh, Role.STUDENT)))
			.andExpect(jsonPath("$.team.onCrew").value(true));

		this.transactions.executeWithoutResult(status -> this.teams.releaseCrew(crewRow));

		assertThat(this.teams.isMember(this.hung, this.minh)).as("an open Team keeps its member").isTrue();
		leave(this.minh).andExpect(status().isNoContent());
	}

	@Test
	void aReservationNeedsTheMentorsOwnMemberAndTheCallersTransaction() {
		UUID other = TeamTestSupport.mentor(this.jdbc, "Người Khác", List.of(REACT), "React");
		UUID loner = TeamTestSupport.student(this.jdbc, "Không Nhóm", true, List.of(REACT), "React", VERIFIED_UNTIL);

		assertThatThrownBy(() -> reserve(UUID.randomUUID(), other, this.minh)).isInstanceOf(ApiException.class)
			.satisfies(ex -> assertThat(((ApiException) ex).reason()).contains("NOT_IN_TEAM"));
		assertThatThrownBy(() -> reserve(UUID.randomUUID(), this.hung, loner)).isInstanceOf(ApiException.class)
			.satisfies(ex -> assertThat(((ApiException) ex).reason()).contains("NOT_IN_TEAM"));
		assertThatThrownBy(() -> this.teams.reserveForCrew(UUID.randomUUID(), this.hung, this.minh, UUID.randomUUID()))
			.isInstanceOf(IllegalTransactionStateException.class);
	}

	@Test
	void aGoneMentorsReservedStudentStaysUntilTheReservationEnds() throws Exception {
		UUID vy = TeamTestSupport.student(this.jdbc, "Lê Thảo Vy", true, List.of(REACT), "React", VERIFIED_UNTIL);
		this.store.insertMembership(this.store.byMentor(this.hung).orElseThrow().id(), vy, this.clock.instant());
		UUID crewRow = UUID.randomUUID();
		reserve(crewRow, this.hung, this.minh);

		IdentityTestSupport.lock(this.jdbc, this.hung, "Vi phạm");
		publish(new AccountLocked(UUID.randomUUID(), this.hung, Role.MENTOR));
		await().atMost(Duration.ofSeconds(10)).until(() -> !this.teams.isMember(this.hung, vy) && settled());

		assertThat(this.teams.isMember(this.hung, this.minh)).isTrue();
		this.transactions.executeWithoutResult(status -> this.teams.releaseCrew(crewRow));
		assertThat(this.teams.isMember(this.hung, this.minh)).isFalse();
		assertThat(leftReason()).isEqualTo("RELEASED");
		assertThat(notificationParam(this.minh, TeamMemberships.RELEASED, "mentor")).isEqualTo("Đỗ Mạnh Hùng");
	}

	@Test
	void aLockedReservedStudentLeavesWhenTheReservationEnds() throws Exception {
		UUID crewRow = UUID.randomUUID();
		reserve(crewRow, this.hung, this.minh);
		IdentityTestSupport.lock(this.jdbc, this.minh, "test");

		publish(new AccountLocked(UUID.randomUUID(), this.minh, Role.STUDENT));
		await().atMost(Duration.ofSeconds(10)).until(this::settled);
		assertThat(this.teams.isMember(this.hung, this.minh)).isTrue();

		this.transactions.executeWithoutResult(status -> this.teams.releaseCrew(crewRow));
		assertThat(this.teams.isMember(this.hung, this.minh)).isFalse();
	}

	@Test
	void aLeaveAndAReservationAtOnceNeverBothWin() throws Exception {
		RequestPostProcessor asMinh = as(this.minh, Role.STUDENT);
		CountDownLatch start = new CountDownLatch(1);
		ExecutorService pool = Executors.newFixedThreadPool(2);
		Future<Integer> leaving = pool.submit(() -> {
			start.await();
			return this.mvc.perform(delete("/api/v1/team/membership").with(asMinh).with(csrf()))
				.andReturn()
				.getResponse()
				.getStatus();
		});
		Future<String> reserving = pool.submit(() -> {
			start.await();
			try {
				reserve(UUID.randomUUID(), this.hung, this.minh);
				return "RESERVED";
			}
			catch (ApiException ex) {
				return ex.reason().orElse("");
			}
		});
		start.countDown();
		int left = leaving.get();
		String reserved = reserving.get();
		pool.shutdown();

		if (left == 204) {
			assertThat(reserved).isEqualTo("NOT_IN_TEAM");
			assertThat(this.jdbc.sql("SELECT count(*) FROM team.crew_reservation").query(Long.class).single()).isZero();
		}
		else {
			assertThat(left).isEqualTo(422);
			assertThat(reserved).isEqualTo("RESERVED");
			assertThat(this.teams.isMember(this.hung, this.minh)).isTrue();
		}
	}

	private void reserve(UUID crewRow, UUID mentor, UUID student) {
		this.transactions.executeWithoutResult(status -> this.teams.reserveForCrew(crewRow, mentor, student, UUID.randomUUID()));
	}

	private ResultActions leave(UUID student) throws Exception {
		return this.mvc.perform(delete("/api/v1/team/membership").with(as(student, Role.STUDENT)).with(csrf()));
	}

	private ResultActions remove(UUID mentor, UUID membershipId) throws Exception {
		return this.mvc.perform(delete("/api/v1/mentor/team/members/" + membershipId).with(as(mentor, Role.MENTOR)).with(csrf()));
	}

	private String leftReason() {
		return this.jdbc.sql("SELECT left_reason FROM team.membership WHERE id = :id")
			.param("id", this.membership).query(String.class).single();
	}

	private String notificationParam(UUID account, String type, String param) {
		return this.jdbc.sql("""
				SELECT params ->> :param FROM notification.notification WHERE account_id = :id AND type = :type
				ORDER BY created_at DESC LIMIT 1
				""").param("param", param).param("id", account).param("type", type).query(String.class).single();
	}

	private void publish(Object event) {
		this.transactions.executeWithoutResult(status -> this.events.publishEvent(event));
	}

	private boolean settled() {
		return this.jdbc.sql("SELECT count(*) FROM platform.event_publication WHERE completion_date IS NULL")
			.query(Long.class)
			.single() == 0;
	}

	private RequestPostProcessor as(UUID account, Role role) {
		return IdentityTestSupport.signedIn(this.sessions, account, role, this.clock.instant());
	}

}
