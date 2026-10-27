package vn.vibefix.team.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
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
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.support.TransactionTemplate;

import vn.vibefix.identity.AccountLocked;
import vn.vibefix.identity.internal.IdentityTestSupport;
import vn.vibefix.shared.ApiException;
import vn.vibefix.support.IntegrationTest;
import vn.vibefix.team.Teams;

/**
 * FR-6, AD-37: leaving, removal, Crew reservations, and the guard every membership end applies, through
 * {@link TeamMemberships} itself: its endpoints answer nothing to every caller since Story 20.3, until Story 21.7
 * deletes Teams.
 */
class TeamMembershipIT extends IntegrationTest {

	private static final LocalDate VERIFIED_UNTIL = LocalDate.of(2026, 9, 30);

	@Autowired
	private JdbcClient jdbc;

	@Autowired
	private TeamMemberships memberships;

	@Autowired
	private TeamRequests requests;

	@Autowired
	private TeamViews views;

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
		this.memberships.leave(this.minh);

		assertThat(this.teams.isMember(this.hung, this.minh)).isFalse();
		assertThat(leftReason()).isEqualTo("LEFT");
		assertThat(notificationParam(this.hung, TeamMemberships.MEMBER_LEFT, "name")).isEqualTo("Trần Quang Minh");
		assertRefused(() -> this.memberships.leave(this.minh), "NOT_FOUND", null);
		UUID thao = TeamTestSupport.mentor(this.jdbc, "Nguyễn Phương Thảo", List.of(REACT),
				"React");
		assertThat(this.requests.requestToJoin(this.minh, thao).id()).isNotNull();
	}

	@Test
	void theMentorRemovesOnlyTheirOwnMembers() throws Exception {
		UUID other = TeamTestSupport.mentor(this.jdbc, "Người Khác", List.of(REACT), "React");
		this.store.ensure(other, this.clock.instant());
		assertRefused(() -> this.memberships.remove(other, this.membership), "NOT_FOUND", null);

		this.memberships.remove(this.hung, this.membership);

		assertThat(leftReason()).isEqualTo("REMOVED");
		assertThat(notificationParam(this.minh, TeamMemberships.MEMBER_REMOVED, "mentor")).isEqualTo("Đỗ Mạnh Hùng");
		assertRefused(() -> this.memberships.remove(this.hung, this.membership), "NOT_FOUND", null);
	}

	@Test
	void aReservedMemberCanNeitherLeaveNorBeRemovedUntilTheReservationEnds() throws Exception {
		UUID crewRow = UUID.randomUUID();
		reserve(crewRow, this.hung, this.minh);
		reserve(crewRow, this.hung, this.minh);
		assertThat(this.jdbc.sql("SELECT count(*) FROM team.crew_reservation").query(Long.class).single())
			.as("reserving a Crew row again changes nothing").isOne();

		assertRefused(() -> this.memberships.leave(this.minh), "GUARD_FAILED", "STUDENT_ON_CREW");
		assertRefused(() -> this.memberships.remove(this.hung, this.membership), "GUARD_FAILED", "STUDENT_ON_CREW");
		assertThat(this.views.mentorTeam(this.hung).members().getFirst().onCrew()).isTrue();
		assertThat(this.views.studentTeam(this.minh).team()).isNotNull().satisfies(team -> assertThat(team.onCrew()).isTrue());

		this.transactions.executeWithoutResult(status -> this.teams.releaseCrew(crewRow));

		assertThat(this.teams.isMember(this.hung, this.minh)).as("an open Team keeps its member").isTrue();
		this.memberships.leave(this.minh);
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
	void aClosedTeamsReservedStudentStaysUntilTheReservationEnds() throws Exception {
		UUID crewRow = UUID.randomUUID();
		reserve(crewRow, this.hung, this.minh);
		UUID team = this.store.byMentor(this.hung).orElseThrow().id();

		this.transactions.executeWithoutResult(status -> this.store.close(team, this.clock.instant()));

		assertThat(this.teams.isMember(this.hung, this.minh)).isTrue();
		this.transactions.executeWithoutResult(status -> this.teams.releaseCrew(crewRow));
		assertThat(this.teams.isMember(this.hung, this.minh)).isFalse();
		assertThat(leftReason()).isEqualTo("RELEASED");
		assertThat(notificationParam(this.minh, TeamMemberships.RELEASED, "mentor")).isEqualTo("Đỗ Mạnh Hùng");
	}

	/** Story 20.4: a lock changes no Team; the reservation's end releases a locked Student. */
	@Test
	void aLockedReservedStudentLeavesWhenTheReservationEnds() throws Exception {
		UUID crewRow = UUID.randomUUID();
		reserve(crewRow, this.hung, this.minh);
		IdentityTestSupport.lock(this.jdbc, this.minh, "test");

		publish(new AccountLocked(UUID.randomUUID(), this.minh, this.clock.instant()));
		await().atMost(Duration.ofSeconds(10)).until(this::settled);
		assertThat(this.teams.isMember(this.hung, this.minh)).isTrue();

		this.transactions.executeWithoutResult(status -> this.teams.releaseCrew(crewRow));
		assertThat(this.teams.isMember(this.hung, this.minh)).isFalse();
	}

	@Test
	void aLeaveAndAReservationAtOnceNeverBothWin() throws Exception {
		CountDownLatch start = new CountDownLatch(1);
		ExecutorService pool = Executors.newFixedThreadPool(2);
		Future<Integer> leaving = pool.submit(() -> {
			start.await();
			try {
				this.memberships.leave(this.minh);
				return 204;
			}
			catch (ApiException ex) {
				return ex.reason().filter("STUDENT_ON_CREW"::equals).isPresent() ? 422 : 500;
			}
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

	/** The command is refused with {@code code} and, when given, {@code reason}. */
	private static void assertRefused(Runnable command, String code, String reason) {
		assertThatThrownBy(command::run).isInstanceOf(ApiException.class).satisfies(ex -> {
			ApiException refused = (ApiException) ex;
			assertThat(refused.code().name()).isEqualTo(code);
			if (reason != null) {
				assertThat(refused.reason()).contains(reason);
			}
		});
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

}
