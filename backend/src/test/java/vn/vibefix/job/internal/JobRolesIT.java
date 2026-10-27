package vn.vibefix.job.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;

import vn.vibefix.identity.internal.IdentityTestSupport;
import vn.vibefix.identity.internal.Role;
import vn.vibefix.job.Crews;
import vn.vibefix.job.JobRole;
import vn.vibefix.job.JobRoles;
import vn.vibefix.job.JobRoles.History;
import vn.vibefix.job.JobRoles.RoleHistory;
import vn.vibefix.shared.Caller;
import vn.vibefix.shared.Kind;
import vn.vibefix.support.IntegrationTest;
import vn.vibefix.support.MutableClock;
import vn.vibefix.team.internal.TeamTestSupport;

/**
 * AD-43, Story 20.2: {@code job.roleOf} gives each account at most one role per Job and agrees with the Job, Crew
 * row and bid tables; an Administrator holds none; each schema backstop refuses a second role; and
 * {@code job.historyOf} counts the Jobs closed with Accepted work per role.
 */
class JobRolesIT extends IntegrationTest {

	private static final LocalDate VERIFIED_UNTIL = LocalDate.of(2026, 9, 30);

	@Autowired
	private JdbcClient jdbc;

	@Autowired
	private JobRoles roles;

	@Autowired
	private Crews crews;

	@Test
	void roleOfGivesEachAccountAtMostOneRolePerJobAndAgreesWithTheTables() {
		UUID customer = IdentityTestSupport.insertAccount(this.jdbc, Role.CUSTOMER, null);
		UUID mentor = TeamTestSupport.mentor(this.jdbc, "Đỗ Mạnh Hùng", List.of(TeamTestSupport.REACT), "React");
		UUID assigned = student("Sinh viên được giao");
		UUID active = student("Sinh viên đang làm");
		// Story 20.4: a lock counts as leaving, so a locked member is a former one.
		UUID locked = student("Sinh viên bị khóa");
		UUID former = student("Sinh viên đã rời");
		UUID declined = student("Sinh viên đã từ chối");
		UUID bidder = TeamTestSupport.mentor(this.jdbc, "Người ứng tuyển", List.of(TeamTestSupport.REACT), "React");
		// The cross-role account: the shim gives it the Customer role, and it is the Mentor of another Job.
		UUID crossRole = IdentityTestSupport.insertAccount(this.jdbc, Role.CUSTOMER, null);
		UUID unrelated = IdentityTestSupport.insertAccount(this.jdbc, Role.CUSTOMER, null);
		UUID admin = IdentityTestSupport.insertAccount(this.jdbc, Role.ADMINISTRATOR, null);

		UUID running = job(customer, mentor, "ACTIVE", "BUG_FIX");
		crewRow(running, assigned, "ASSIGNED", false);
		crewRow(running, active, "ACTIVE", true);
		crewRow(running, locked, "RELEASED", true);
		IdentityTestSupport.lock(this.jdbc, locked, "Vi phạm quy tắc");
		crewRow(running, former, "RELEASED", true);
		crewRow(running, declined, "RELEASED", false);
		UUID open = job(crossRole, null, "OPEN", "BUG_FIX");
		bid(open, bidder);
		bid(open, mentor);
		UUID other = job(customer, crossRole, "ACTIVE", "BUG_FIX");
		crewRow(other, former, "ACTIVE", true);
		// A relation row naming an Administrator, which no command writes: roleOf still answers NONE.
		crewRow(other, admin, "ASSIGNED", false);
		UUID missing = UUID.randomUUID();

		Map<UUID, Map<UUID, String>> expected = new LinkedHashMap<>();
		expected.put(running, Map.of(customer, "CUSTOMER", mentor, "MENTOR", assigned, "CREW ASSIGNED", active,
				"CREW ACTIVE", locked, "CREW FORMER", former, "CREW FORMER"));
		expected.put(open, Map.of(crossRole, "CUSTOMER"));
		expected.put(other, Map.of(customer, "CUSTOMER", crossRole, "MENTOR", former, "CREW ACTIVE"));
		expected.put(missing, Map.of());
		List<UUID> accounts = List.of(customer, mentor, assigned, active, locked, former, declined, bidder, crossRole,
				unrelated, admin);

		List<String> mismatches = new ArrayList<>();
		for (Map.Entry<UUID, Map<UUID, String>> job : expected.entrySet()) {
			for (UUID account : accounts) {
				String want = job.getValue().getOrDefault(account, "NONE");
				String byId = describe(this.roles.roleOf(job.getKey(), account));
				boolean administrator = account.equals(admin);
				Caller caller = administrator ? Caller.account(account, Kind.ADMINISTRATOR)
						: Caller.account(account, Kind.USER);
				String byCaller = describe(this.roles.roleOf(job.getKey(), caller));
				if (!want.equals(byId) || !want.equals(byCaller)) {
					mismatches.add(job.getKey() + "/" + account + ": want " + want + ", by ID " + byId + ", by caller "
							+ byCaller);
				}
			}
			assertThat(describe(this.roles.roleOf(job.getKey(), Caller.anonymous()))).isEqualTo("NONE");
		}
		assertThat(mismatches).isEmpty();
		assertThat(this.crews.crewRelationship(other, admin).kind()).as("the relation row the overload ignores exists")
			.isEqualTo(Crews.Relationship.ASSIGNED);

		assertThat(overlaps()).as("no Job's Customer, Mentor and counted Crew members overlap, anywhere").isEmpty();
		assertThat(this.jdbc.sql("""
				SELECT count(*) FROM matching.application a JOIN job.job j ON j.id = a.job_id
				WHERE a.mentor_account_id = j.customer_account_id OR a.customer_account_id <> j.customer_account_id
				""").query(Long.class).single()).as("no bid by a Job's own Customer").isZero();
	}

	@Test
	void eachSchemaBackstopRefusesASecondRoleInAJob() {
		UUID customer = IdentityTestSupport.insertAccount(this.jdbc, Role.CUSTOMER, null);
		UUID mentor = TeamTestSupport.mentor(this.jdbc, "Đỗ Mạnh Hùng", List.of(TeamTestSupport.REACT), "React");
		UUID member = student("Lê Thảo Vy");
		UUID declined = student("Bùi Gia Huy");
		UUID job = job(customer, mentor, "ACTIVE", "BUG_FIX");
		crewRow(job, member, "ACTIVE", true);
		crewRow(job, declined, "RELEASED", false);

		assertRefused("the Mentor is the Customer", () -> this.jdbc
			.sql("UPDATE job.job SET mentor_account_id = customer_account_id WHERE id = :id")
			.param("id", job(customer, mentor, "SELECTING", "BUG_FIX"))
			.update());
		assertRefused("a Crew row of the Customer", () -> insertCrewRow(job, customer, "ASSIGNED"));
		assertRefused("a Crew row of the Mentor", () -> insertCrewRow(job, mentor, "ASSIGNED"));
		assertRefused("a Crew row moved onto the Customer", () -> this.jdbc
			.sql("UPDATE job.crew_row SET member_account_id = :customer WHERE job_id = :job AND member_account_id = :member")
			.param("customer", customer)
			.param("job", job)
			.param("member", member)
			.update());
		assertRefused("a Mentor recorded with a counted Crew row", () -> this.jdbc
			.sql("UPDATE job.job SET mentor_account_id = :member WHERE id = :id")
			.param("member", member)
			.param("id", job)
			.update());
		UUID bidder = TeamTestSupport.mentor(this.jdbc, "Người ứng tuyển", List.of(TeamTestSupport.REACT), "React");
		UUID open = job(customer, null, "OPEN", "BUG_FIX");
		assertRefused("a bid by the Job's Customer", () -> bid(open, customer));
		assertRefused("an invitation of the Job's Customer", () -> invite(open, customer));

		bid(open, bidder);
		// A released row never activated is not counted (AD-43): it holds no role, so it blocks nothing.
		this.jdbc.sql("UPDATE job.job SET mentor_account_id = :declined WHERE id = :id")
			.param("declined", declined)
			.param("id", job)
			.update();
		assertThat(describe(this.roles.roleOf(job, declined))).isEqualTo("MENTOR");
		assertThat(this.jdbc.sql("UPDATE job.crew_row SET member_account_id = :declined WHERE member_account_id = :declined")
			.param("declined", declined)
			.update()).as("the Mentor's uncounted row stays writable").isOne();
		assertThat(overlaps()).isEmpty();
	}

	@Test
	void historyOfCountsTheJobsClosedWithAcceptedWorkPerRole() {
		UUID mentor = TeamTestSupport.mentor(this.jdbc, "Đỗ Mạnh Hùng", List.of(TeamTestSupport.REACT), "React");
		UUID member = student("Lê Thảo Vy");
		UUID customer = IdentityTestSupport.insertAccount(this.jdbc, Role.CUSTOMER, null);

		UUID closed = finished(customer, mentor, "CLOSED", true, "NEW_FEATURE");
		milestone(closed, "ACCEPTED", false);
		crewRow(closed, member, "RELEASED", true);
		UUID cancelledAfterClosing = finished(customer, mentor, "CANCELLED", true, "NEW_FEATURE", "BUG_FIX");
		milestone(cancelledAfterClosing, "SETTLED", false);
		milestone(cancelledAfterClosing, "CANCELLED", false);
		UUID nothingAccepted = finished(customer, mentor, "CLOSED", true, "DOCUMENTATION");
		milestone(nothingAccepted, "CANCELLED", false);
		crewRow(nothingAccepted, member, "RELEASED", true);
		UUID removedOnly = finished(customer, mentor, "CLOSED", true, "DOCUMENTATION");
		milestone(removedOnly, "ACCEPTED", true);
		UUID cancelledByJ10 = finished(customer, mentor, "CANCELLED", false, "DOCUMENTATION");
		milestone(cancelledByJ10, "CANCELLED", false);
		UUID stillRunning = job(customer, mentor, "ACTIVE", "SECURITY_TESTING");
		milestone(stillRunning, "ACCEPTED", false);
		UUID neverActivated = finished(customer, mentor, "CLOSED", true, "MODIFICATION");
		milestone(neverActivated, "ACCEPTED", false);
		crewRow(neverActivated, member, "RELEASED", false);

		History mentorHistory = this.roles.historyOf(mentor);
		assertThat(mentorHistory.mentor().jobs()).isEqualTo(3);
		assertThat(mentorHistory.mentor().jobTypes())
			.isEqualTo(Map.of("NEW_FEATURE", 2, "BUG_FIX", 1, "MODIFICATION", 1));
		assertThat(mentorHistory.crew()).isEqualTo(RoleHistory.EMPTY);
		History memberHistory = this.roles.historyOf(member);
		assertThat(memberHistory.crew()).isEqualTo(new RoleHistory(1, Map.of("NEW_FEATURE", 1)));
		assertThat(memberHistory.mentor()).isEqualTo(RoleHistory.EMPTY);
		assertThat(this.roles.historyOf(customer)).as("a Customer's Jobs are no history")
			.isEqualTo(new History(RoleHistory.EMPTY, RoleHistory.EMPTY));
	}

	@Test
	void theIoMatrixHistoryRow() {
		UUID mentor = TeamTestSupport.mentor(this.jdbc, "Đỗ Mạnh Hùng", List.of(TeamTestSupport.REACT), "React");
		UUID customer = IdentityTestSupport.insertAccount(this.jdbc, Role.CUSTOMER, null);
		UUID otherMentor = TeamTestSupport.mentor(this.jdbc, "Phạm Thu Hà", List.of(TeamTestSupport.REACT), "React");
		milestone(finished(customer, mentor, "CLOSED", true, "NEW_FEATURE"), "ACCEPTED", false);
		milestone(finished(customer, mentor, "CLOSED", true, "NEW_FEATURE", "BUG_FIX"), "ACCEPTED", false);
		milestone(finished(customer, mentor, "CLOSED", true, "NEW_FEATURE"), "CANCELLED", false);
		UUID crewed = finished(customer, otherMentor, "CLOSED", true, "DOCUMENTATION");
		milestone(crewed, "ACCEPTED", false);
		crewRow(crewed, mentor, "RELEASED", true);

		History history = this.roles.historyOf(mentor);

		assertThat(history.mentor()).isEqualTo(new RoleHistory(2, Map.of("NEW_FEATURE", 2, "BUG_FIX", 1)));
		assertThat(history.crew().jobs()).isOne();
	}

	/** Every (Job, account) pair with more than one relation: Customer, Mentor, or a counted Crew row. */
	private List<String> overlaps() {
		return this.jdbc.sql("""
				SELECT job_id || ' ' || account_id FROM (
				    SELECT id AS job_id, customer_account_id AS account_id, 'CUSTOMER' AS kind FROM job.job
				    UNION ALL
				    SELECT id, mentor_account_id, 'MENTOR' FROM job.job WHERE mentor_account_id IS NOT NULL
				    UNION ALL
				    SELECT DISTINCT job_id, member_account_id, 'CREW' FROM job.crew_row
				    WHERE status <> 'RELEASED' OR activated_at IS NOT NULL
				) relation
				GROUP BY job_id, account_id HAVING count(*) > 1
				""").query(String.class).list();
	}

	private static void assertRefused(String what, Runnable write) {
		assertThatThrownBy(write::run).as(what).isInstanceOf(DataIntegrityViolationException.class);
	}

	private static String describe(JobRole role) {
		return switch (role) {
			case JobRole.Customer customer -> "CUSTOMER";
			case JobRole.Mentor mentor -> "MENTOR";
			case JobRole.Crew crew -> "CREW " + crew.relationship().kind();
			case JobRole.None none -> "NONE";
		};
	}

	private UUID student(String name) {
		return TeamTestSupport.student(this.jdbc, name, true, List.of(TeamTestSupport.REACT), "React", VERIFIED_UNTIL);
	}

	/** A Job on its own Product (one running Code Job per Product), its Mentor set from Selecting on. */
	private UUID job(UUID customer, @Nullable UUID mentor, String state, String... types) {
		return job(customer, mentor, state, false, types);
	}

	/** A Job that ended: Closed, or Cancelled after Closing when {@code closing} (J9), else Cancelled by J10. */
	private UUID finished(UUID customer, UUID mentor, String state, boolean closing, String... types) {
		return job(customer, mentor, state, closing, types);
	}

	private UUID job(UUID customer, @Nullable UUID mentor, String state, boolean closing, String... types) {
		UUID id = UUID.randomUUID();
		Instant at = MutableClock.DEFAULT_INSTANT.minus(Duration.ofDays(30));
		this.jdbc.sql("""
				INSERT INTO job.job (id, product_id, customer_account_id, state, title, problem, expected, app_users,
				    job_types, budget, deadline, rules, selection_days, opened_at, lapses_at, mentor_account_id,
				    selected_application_id, selected_at, closing_at, closed_at, cancelled_at, version, created_at, updated_at)
				VALUES (:id, :product, :customer, :state, 'Việc của vai trò', 'Lỗi', 'Đúng', 'Khách', CAST(:types AS TEXT[]),
				    'UNDER_2M', DATE '2026-12-01', '{}'::jsonb, 14, :at, :lapses, :mentor, :application, :selected,
				    :closing, :closed, :cancelled, 1, :at, :at)
				""")
			.param("id", id)
			.param("product", product(customer))
			.param("customer", customer)
			.param("state", state)
			.param("types", types)
			.param("at", Timestamp.from(at))
			.param("lapses", Timestamp.from(at.plus(Duration.ofDays(14))))
			.param("mentor", mentor)
			.param("application", mentor == null ? null : UUID.randomUUID())
			.param("selected", mentor == null ? null : Timestamp.from(at))
			.param("closing", closing ? Timestamp.from(at.plus(Duration.ofDays(10))) : null)
			.param("closed", "CLOSED".equals(state) ? Timestamp.from(at.plus(Duration.ofDays(12))) : null)
			.param("cancelled", "CANCELLED".equals(state) ? Timestamp.from(at.plus(Duration.ofDays(12))) : null)
			.update();
		return id;
	}

	private void crewRow(UUID job, UUID member, String status, boolean activated) {
		Instant at = MutableClock.DEFAULT_INSTANT.minus(Duration.ofDays(20));
		boolean released = "RELEASED".equals(status);
		this.jdbc.sql("""
				INSERT INTO job.crew_row (id, job_id, member_account_id, status, signed_at, activated_at, window_end,
				    released_at, released_reason, created_at)
				VALUES (:id, :job, :member, :status, :signed, :activated, :end, :released, :reason, :at)
				""")
			.param("id", UUID.randomUUID())
			.param("job", job)
			.param("member", member)
			.param("status", status)
			.param("signed", activated ? Timestamp.from(at) : null)
			.param("activated", activated ? Timestamp.from(at) : null)
			.param("end", activated && released ? Timestamp.from(at.plus(Duration.ofDays(5))) : null)
			.param("released", released ? Timestamp.from(at.plus(Duration.ofDays(5))) : null)
			.param("reason", released ? (activated ? "JOB_ENDED" : "DECLINED") : null)
			.param("at", Timestamp.from(at))
			.update();
	}

	private void insertCrewRow(UUID job, UUID member, String status) {
		this.jdbc.sql("""
				INSERT INTO job.crew_row (id, job_id, member_account_id, status, created_at) VALUES (:id, :job, :member, :status, now())
				""").param("id", UUID.randomUUID()).param("job", job).param("member", member).param("status", status).update();
	}

	private void milestone(UUID job, String state, boolean removed) {
		Instant at = MutableClock.DEFAULT_INSTANT.minus(Duration.ofDays(25));
		boolean funded = !"PLANNED".equals(state) && !"CANCELLED".equals(state);
		this.jdbc.sql("""
				INSERT INTO job.milestone (id, job_id, position, title, price_vnd, revision_rounds, created_at, state, funded_at,
				    crew_terms_version_no, accepted_at, settled_at, cancelled_at, removed_at)
				VALUES (:id, :job, (SELECT count(*) FROM job.milestone WHERE job_id = :job), 'Giai đoạn', 800000, 2, :at,
				    :state, :funded, :pin, :accepted, :settled, :cancelled, :removed)
				""")
			.param("id", UUID.randomUUID())
			.param("job", job)
			.param("at", Timestamp.from(at))
			.param("state", state)
			.param("funded", funded ? Timestamp.from(at) : null)
			.param("pin", funded ? 1 : null)
			.param("accepted", "ACCEPTED".equals(state) ? Timestamp.from(at.plus(Duration.ofDays(1))) : null)
			.param("settled", "SETTLED".equals(state) ? Timestamp.from(at.plus(Duration.ofDays(1))) : null)
			.param("cancelled", "CANCELLED".equals(state) ? Timestamp.from(at.plus(Duration.ofDays(1))) : null)
			.param("removed", removed ? Timestamp.from(at.plus(Duration.ofDays(2))) : null)
			.update();
	}

	private void bid(UUID job, UUID bidder) {
		this.jdbc.sql("""
				INSERT INTO matching.application (id, job_id, product_id, customer_account_id, mentor_account_id, status,
				    price_vnd, timeline_days, approach, public_code_notice_version, submitted_at)
				SELECT :id, j.id, j.product_id, j.customer_account_id, :bidder, 'SUBMITTED', 800000, 7, 'Cách làm', '1.0', now()
				FROM job.job j WHERE j.id = :job
				""").param("id", UUID.randomUUID()).param("job", job).param("bidder", bidder).update();
	}

	private void invite(UUID job, UUID invitee) {
		this.jdbc.sql("""
				INSERT INTO matching.invitation (id, job_id, product_id, customer_account_id, mentor_account_id, status, invited_at)
				SELECT :id, j.id, j.product_id, j.customer_account_id, :invitee, 'OPEN', now() FROM job.job j WHERE j.id = :job
				""").param("id", UUID.randomUUID()).param("job", job).param("invitee", invitee).update();
	}

	private UUID product(UUID owner) {
		UUID id = UUID.randomUUID();
		Timestamp now = Timestamp.from(MutableClock.DEFAULT_INSTANT);
		this.jdbc.sql("""
				INSERT INTO product.product (id, customer_account_id, name, description, ai_tools, repository_github_id,
				    repository_full_name, repository_owner_type, repository_default_branch, public_notice_version,
				    copy_permission_version, detection_notice_version, acknowledged_at, created_at)
				VALUES (:id, :owner, 'Sổ đặt bàn Quán Mây', 'Đặt bàn.', ARRAY['LOVABLE'], :github,
				    'lan/so-dat-ban', 'User', 'main', '1.0', '1.0', '1.0', :now, :now)
				""")
			.param("id", id)
			.param("owner", owner)
			.param("github", ThreadLocalRandom.current().nextLong(1, Long.MAX_VALUE))
			.param("now", now)
			.update();
		return id;
	}

}
