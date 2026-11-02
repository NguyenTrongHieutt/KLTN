package vn.vibefix.job.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.session.Session;
import org.springframework.session.SessionRepository;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.support.TransactionTemplate;

import com.jayway.jsonpath.JsonPath;

import vn.vibefix.authz.JobContent;
import vn.vibefix.identity.internal.IdentityTestSupport;
import vn.vibefix.identity.internal.Role;
import vn.vibefix.job.CrewPicks;
import vn.vibefix.job.CrewPicks.Contribution;
import vn.vibefix.job.MilestoneHandovers;
import vn.vibefix.job.MilestoneSplits;
import vn.vibefix.job.MilestoneSplits.SplitParty;
import vn.vibefix.job.internal.JobLifecycle.Actor;
import vn.vibefix.notification.Audience;
import vn.vibefix.notification.TodoRequest;
import vn.vibefix.notification.Todos;
import vn.vibefix.payment.Releases;
import vn.vibefix.shared.ErasureOutcome;
import vn.vibefix.shared.PersonalData;
import vn.vibefix.shared.PersonalDataSection;
import vn.vibefix.support.HeldCrewJob;
import vn.vibefix.support.IntegrationTest;
import vn.vibefix.support.PostgresTestDatabase;
import vn.vibefix.team.internal.TeamTestSupport;
import vn.vibefix.timers.internal.DeadlinePoller;

/**
 * Story 21.6 (FR-21, FR-59, FR-66, FR-75, FR-77, FR-82, AD-11, AD-14, AD-41, R3, R8, R9, R15), SM-8 "a member leaving
 * mid-Milestone" and "the last member leaving with the Mentor carrying on": a member leaves a running Job at once and for
 * good, with an optional reason the Mentor alone reads; their write access ends, their open Work Items become unassigned,
 * the data-deletion confirmation is asked in every state, the Customer hears the GitHub username only and gets one
 * revocation to-do per External Access item, and they stay a party of the Milestone's split. Nobody rejoins, and when nobody
 * is left the Mentor carries on and the Customer is told.
 */
@TestPropertySource(properties = "vibefix.features.large-crews=true")
class CrewLeaveIT extends IntegrationTest {

	private static final String REASON = "Mình nhận thực tập toàn thời gian từ tuần sau";

	private static final UUID SUPABASE = UUID.randomUUID();

	private static final UUID VERCEL = UUID.randomUUID();

	@Autowired
	private MockMvc mvc;

	@Autowired
	private JdbcClient jdbc;

	@Autowired
	private SessionRepository<? extends Session> sessions;

	@Autowired
	private TransactionTemplate transactions;

	@Autowired
	private Todos todos;

	@Autowired
	private JobLifecycle lifecycle;

	@Autowired
	private CrewPicks picks;

	@Autowired
	private PersonalData personalData;

	@Autowired
	private MilestoneHandovers handovers;

	@Autowired
	private MilestoneSplits splits;

	@Autowired
	private Releases releases;

	@Autowired
	private DeadlinePoller poller;

	private HeldCrewJob world;

	private UUID job;

	private UUID lan;

	private UUID hung;

	private UUID vy;

	private UUID minh;

	@BeforeEach
	void aHeldMilestoneOfACrewOfThree() {
		this.world = HeldCrewJob.create(this.jdbc, this.clock.instant());
		this.job = this.world.job();
		this.lan = this.world.lan();
		this.hung = this.world.hung();
		this.vy = this.world.vy();
		this.minh = this.world.minh();
	}

	// ---- Leaving -------------------------------------------------------------------------------

	@Test
	void aMemberLeavesAtOnceAndEveryoneHearsWhatConcernsThem() throws Exception {
		JobContent.board(this.jdbc, this.job, this.world.product(), "SDB");
		UUID todo = item("Việc cần làm", this.vy);
		UUID doing = item("Việc đang làm", this.vy);
		UUID inReview = item("Việc chờ duyệt", this.vy);
		UUID returned = item("Việc bị trả lại", this.vy);
		UUID done = item("Việc đã xong", this.vy);
		UUID others = item("Việc của Minh", this.minh);
		setStatus(doing, "IN_PROGRESS");
		setStatus(inReview, "IN_REVIEW");
		setStatus(returned, "IN_PROGRESS");
		setStatus(done, "DONE");
		this.transactions.executeWithoutResult(tx -> this.todos.open(new TodoRequest("work.fix_returned_item", this.vy,
				Audience.CREW_ACTIVE, returned, Map.of("key", "SDB-5"), "/cong-viec/" + this.job + "/bang", null, this.job)));
		UUID grant = grant(this.vy);
		UUID elsewhere = otherJobsItem(this.vy);
		long version = count("SELECT version FROM job.job WHERE id = '" + this.job + "'");
		long crewVersion = count("SELECT crew_version FROM job.job WHERE id = '" + this.job + "'");
		this.clock.advance(Duration.ofMinutes(1));

		leave(this.vy, REASON).andExpect(status().isNoContent());

		assertThat(text("SELECT status || ' ' || released_reason FROM job.crew_row WHERE member_account_id = '" + this.vy + "'"))
			.isEqualTo("RELEASED LEFT");
		assertThat(this.jdbc.sql("SELECT window_end FROM job.crew_row WHERE member_account_id = :id").param("id", this.vy)
			.query(Timestamp.class).single().toInstant()).isEqualTo(this.clock.instant());
		assertThat(count("SELECT version FROM job.job WHERE id = '" + this.job + "'")).isEqualTo(version + 1);
		assertThat(count("SELECT crew_version FROM job.job WHERE id = '" + this.job + "'")).isEqualTo(crewVersion + 1);
		assertThat(count("SELECT count(*) FROM audit.audit_entry WHERE action = 'job.crew_member_left'")).isOne();
		awaitListeners();

		// FR-82, R15: the Mentor and the other members hear the name; the Customer the GitHub username only.
		for (UUID told : List.of(this.hung, this.minh)) {
			assertThat(notices("job.crew_member_left", told)).isOne();
		}
		assertThat(notices("job.crew_member_left", this.vy)).isZero();
		assertThat(notices("job.crew_login_removed", this.lan)).isOne();
		assertThat(text("SELECT params::text FROM notification.notification WHERE type = 'job.crew_login_removed'"))
			.contains(TeamTestSupport.login(this.jdbc, this.vy))
			.doesNotContain("Trần Thảo Vy");
		assertThat(notices("job.crew_empty", this.lan)).as("a member is left").isZero();
		assertThat(count("SELECT count(*) FROM notification.notification WHERE type = 'job.party_locked'")).isZero();

		// FR-21: Story 7.7's revocation runs from the event: REVOKING at once, its operation reads the removal back.
		assertThat(text("SELECT status FROM repo.job_grant WHERE id = '" + grant + "'")).isEqualTo("REVOKING");

		// Every status but Done goes unassigned, each with a history entry by vibefix; nothing reassigns.
		for (UUID item : List.of(todo, doing, inReview, returned)) {
			assertThat(this.jdbc.sql("SELECT assignee_account_id FROM work.work_item WHERE id = :id").param("id", item)
				.query((rs, n) -> rs.getObject(1, UUID.class)).optional()).isEmpty();
			assertThat(count("""
					SELECT count(*) FROM work.work_item_history WHERE work_item_id = '%s' AND action = 'UPDATED'
					  AND actor_account_id IS NULL AND changes -> 'assigneeId' ->> 'from' = '%s'
					""".formatted(item, this.vy))).isOne();
		}
		assertThat(assignee(done)).isEqualTo(this.vy);
		assertThat(assignee(others)).isEqualTo(this.minh);
		assertThat(assignee(elsewhere)).as("another Job's Board").isEqualTo(this.vy);
		assertThat(openTodos("work.fix_returned_item", this.vy)).isZero();
		assertThat(count("SELECT count(*) FROM notification.todo WHERE todo_key = 'work.reassign_work_items'")).isZero();

		// FR-66: the data-deletion confirmation is asked at once, on their JOB-1.
		assertThat(openTodos("repo.confirm_data_deletion", this.vy)).isOne();
		assertThat(notices("repo.data_deletion_confirmation_pending", this.vy)).isOne();
		assertThat(text("SELECT link FROM notification.todo WHERE todo_key = 'repo.confirm_data_deletion'"))
			.isEqualTo("/cong-viec/" + this.job);

		// UX-DR87: "· đã rời", and the Mentor reads who left open Work Items behind.
		role(this.vy).andExpect(jsonPath("$.crewKind").value("FORMER")).andExpect(jsonPath("$.left").value(true));
		me(this.vy).andExpect(status().isOk())
			.andExpect(jsonPath("$.former").value(true))
			.andExpect(jsonPath("$.canLeave").value(false))
			.andExpect(jsonPath("$.partyMilestones.length()").value(0));
		for (String read : List.of("/api/v1/boards/" + this.job + "/work-items", "/api/v1/boards/" + this.job + "/progress")) {
			this.mvc.perform(get(read).with(as(this.hung, Role.MENTOR)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.leavers.length()").value(1))
				.andExpect(jsonPath("$.leavers[0].name").value("Trần Thảo Vy"));
		}
		this.mvc.perform(get("/api/v1/boards/" + this.job + "/work-items").with(as(this.minh, Role.STUDENT)))
			.andExpect(jsonPath("$.leavers.length()").value(0));

		// The line goes once the Mentor has given every open Work Item to someone else.
		for (UUID item : List.of(todo, doing, inReview, returned)) {
			assign(item, this.minh).andExpect(status().isOk());
		}
		this.mvc.perform(get("/api/v1/boards/" + this.job + "/progress").with(as(this.hung, Role.MENTOR)))
			.andExpect(jsonPath("$.leavers.length()").value(0));
	}

	/** The reason (FR-82): the Mentor reads it on JOB-9, and no notice, to-do, event or other view holds it. */
	@Test
	void theReasonIsTheMentorsAlone() throws Exception {
		JobContent.board(this.jdbc, this.job, this.world.product(), "SDB");
		leave(this.vy, "  " + REASON + "  ").andExpect(status().isNoContent());
		awaitListeners();

		assertThat(text("SELECT left_note FROM job.crew_row WHERE member_account_id = '" + this.vy + "'")).isEqualTo(REASON);
		String crew = this.mvc.perform(get("/api/v1/mentor/jobs/" + this.job + "/recruitment").with(as(this.hung, Role.MENTOR)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.crew[?(@.status == 'RELEASED')].leftNote").value(REASON))
			.andReturn().getResponse().getContentAsString();
		assertThat(crew).contains(REASON);
		for (String table : List.of("notification.notification", "notification.todo", "platform.event_publication")) {
			assertThat(count("SELECT count(*) FROM " + table + " t WHERE t::text LIKE '%thực tập%'")).as(table).isZero();
		}
		assertThat(count("SELECT count(*) FROM audit.audit_entry t WHERE t::text LIKE '%thực tập%'")).isZero();
		for (RequestPostProcessor reader : List.of(as(this.lan, Role.CUSTOMER), as(this.minh, Role.STUDENT),
				as(this.vy, Role.STUDENT), as(this.hung, Role.MENTOR))) {
			for (String read : List.of("/api/v1/jobs/" + this.job + "/role", "/api/v1/boards/" + this.job + "/progress",
					"/api/v1/jobs/" + this.job + "/crew/me", "/api/v1/mentor/jobs/" + this.job + "/crew", "/api/v1/my-jobs",
					"/api/v1/notifications", "/api/v1/todos", "/api/v1/jobs/" + this.job + "/agreement")) {
				assertThat(this.mvc.perform(get(read).with(reader)).andReturn().getResponse().getContentAsString()).as(read)
					.doesNotContain("thực tập");
			}
		}
		this.mvc.perform(get("/api/v1/mentor/jobs/" + this.job + "/recruitment").with(as(this.lan, Role.CUSTOMER)))
			.andExpect(status().isNotFound());
	}

	@Test
	void aBlankReasonMeansNoneAndAReasonTooLongIsRefused() throws Exception {
		leave(this.vy, "x".repeat(501)).andExpect(status().isBadRequest());
		assertThat(text("SELECT status FROM job.crew_row WHERE member_account_id = '" + this.vy + "'")).isEqualTo("ACTIVE");

		leave(this.vy, "   ").andExpect(status().isNoContent());
		assertThat(count("SELECT count(*) FROM job.crew_row WHERE left_note IS NOT NULL")).isZero();
		awaitListeners();
	}

	@Test
	void inClosingNothingChanges() throws Exception {
		this.jdbc.sql("UPDATE job.job SET state = 'CLOSING', closing_at = now() WHERE id = :id").param("id", this.job).update();
		long version = count("SELECT version FROM job.job WHERE id = '" + this.job + "'");

		leave(this.vy, REASON).andExpect(status().is(422))
			.andExpect(jsonPath("$.code").value("GUARD_FAILED"))
			.andExpect(jsonPath("$.reason").value("LEAVE_CLOSED"));

		assertThat(text("SELECT status FROM job.crew_row WHERE member_account_id = '" + this.vy + "'")).isEqualTo("ACTIVE");
		assertThat(count("SELECT version FROM job.job WHERE id = '" + this.job + "'")).isEqualTo(version);
		me(this.vy).andExpect(jsonPath("$.canLeave").value(false));
	}

	@Test
	void onlyAnActiveMemberLeaves() throws Exception {
		UUID picked = JobContent.member(this.jdbc, "Phan Văn An");
		JobContent.crewRow(this.jdbc, this.job, picked, "ASSIGNED", null);
		UUID stranger = JobContent.member(this.jdbc, "Ngô Bá Khá");
		leave(this.vy, null).andExpect(status().isNoContent());

		leave(this.vy, null).andExpect(status().isNotFound());
		for (UUID nobody : List.of(picked, stranger, this.lan, this.hung)) {
			leave(nobody, null).andExpect(status().isNotFound());
		}
		for (UUID nobody : List.of(picked, stranger, this.lan, this.hung)) {
			me(nobody).andExpect(status().isNotFound());
		}
		assertThat(text("SELECT status FROM job.crew_row WHERE member_account_id = '" + picked + "'")).isEqualTo("ASSIGNED");
		awaitListeners();
	}

	@Test
	void aBusyMarkerOrAFreezeBlocksNothing() throws Exception {
		this.jdbc.sql("""
				UPDATE job.job SET busy_operation_key = 'repo.verify:test', busy_purpose = 'JOB_VERIFY', busy_since = now() WHERE id = :id
				""").param("id", this.job).update();
		leave(this.vy, null).andExpect(status().isNoContent());

		this.jdbc.sql("UPDATE job.job SET frozen_at = now() WHERE id = :id").param("id", this.job).update();
		leave(this.minh, null).andExpect(status().isNoContent());
		awaitListeners();

		assertThat(count("SELECT count(*) FROM job.crew_row WHERE released_reason = 'LEFT'")).isEqualTo(2);
	}

	/** R3: a leaver's application is refused, a pick too, and the trigger is the backstop. */
	@Test
	void aLeaverNeverRejoins() throws Exception {
		leave(this.vy, null).andExpect(status().isNoContent());
		awaitListeners();
		this.jdbc.sql("""
				UPDATE job.job SET recruiting = true, recruiting_since = now(), open_slots = 1 WHERE id = :id
				""").param("id", this.job).update();

		this.mvc.perform(post("/api/v1/mentor/job-posts/" + this.job + "/crew-applications").with(as(this.vy, Role.STUDENT))
			.with(csrf())
			.contentType(MediaType.APPLICATION_JSON)
			.content("""
					{"note":"Mình muốn quay lại.","skillIds":["%s"],"publicCodeAcknowledged":true}
					""".formatted(TeamTestSupport.REACT)))
			.andExpect(status().is(422))
			.andExpect(jsonPath("$.reason").value("CREW_REJOIN_REFUSED"));
		assertThatThrownBy(() -> this.transactions.executeWithoutResult(tx -> this.picks.addCrew(this.hung, this.job,
				UUID.randomUUID(), this.vy, Contribution.NONE))).hasMessageContaining("GUARD_FAILED");
		assertThatThrownBy(() -> this.jdbc.sql("""
				INSERT INTO job.crew_row (id, job_id, member_account_id, status, signed_at, activated_at, created_at)
				VALUES (:id, :job, :member, 'ACTIVE', now(), now(), now())
				""").param("id", UUID.randomUUID()).param("job", this.job).param("member", this.vy).update())
			.isInstanceOf(DataIntegrityViolationException.class)
			.hasMessageContaining("no rejoining");
	}

	// ---- The Crew empties ----------------------------------------------------------------------

	/** Q7, R8, SM-8: the last member leaves after J6; the Mentor carries on, the Customer is told once, no Dispute opens. */
	@ParameterizedTest
	@ValueSource(strings = { "STARTING", "ACTIVE" })
	void theLastMemberLeavesAfterJ6AndTheMentorCarriesOn(String state) throws Exception {
		this.jdbc.sql("UPDATE job.job SET state = :state WHERE id = :id").param("state", state).param("id", this.job).update();
		leave(this.vy, null).andExpect(status().isNoContent());
		awaitListeners();
		assertThat(notices("job.crew_empty", this.lan)).as("Minh is left").isZero();
		role(this.hung).andExpect(jsonPath("$.noCrew").value(false));

		leave(this.minh, null).andExpect(status().isNoContent());
		awaitListeners();

		assertThat(notices("job.crew_empty", this.lan)).isOne();
		assertThat(count("SELECT count(*) FROM dispute.dispute WHERE job_id = '" + this.job + "'")).isZero();
		assertThat(text("SELECT state FROM job.job WHERE id = '" + this.job + "'")).isEqualTo(state);
		role(this.hung).andExpect(jsonPath("$.noCrew").value(true));
		role(this.lan).andExpect(jsonPath("$.noCrew").value(true));
		role(this.vy).andExpect(jsonPath("$.noCrew").value(false));

		// It goes when a member signs.
		UUID an = JobContent.member(this.jdbc, "Phan Văn An");
		JobContent.crewRow(this.jdbc, this.job, an, "ACTIVE", null);
		role(this.hung).andExpect(jsonPath("$.noCrew").value(false));
		role(this.lan).andExpect(jsonPath("$.noCrew").value(false));
	}

	/** J7 itself reads no Crew; {@code JobSetupIT} proves that Verify gets a Code Job there with the Mentor alone. */
	@Test
	void j7ReadsNoCrewOnAJobWithNoRepositories() throws Exception {
		this.jdbc.sql("UPDATE job.job SET state = 'STARTING' WHERE id = :id").param("id", this.job).update();
		leave(this.vy, null).andExpect(status().isNoContent());
		leave(this.minh, null).andExpect(status().isNoContent());
		awaitListeners();
		long version = count("SELECT version FROM job.job WHERE id = '" + this.job + "'");

		assertThat(this.lifecycle.tryApply(Transition.J7, this.job, Actor.SYSTEM, Trigger.SYSTEM, version, Map.of(J7Activate.ACCESS_VERIFIED, true)))
			.isPresent();

		assertThat(text("SELECT state FROM job.job WHERE id = '" + this.job + "'")).isEqualTo("ACTIVE");
		awaitListeners();
		role(this.hung).andExpect(jsonPath("$.noCrew").value(true));
	}

	@Test
	void theCustomerCanStillCancelOrOpenADisputeWithNoCrewLeft() throws Exception {
		leave(this.vy, null).andExpect(status().isNoContent());
		leave(this.minh, null).andExpect(status().isNoContent());
		awaitListeners();
		long version = count("SELECT version FROM job.job WHERE id = '" + this.job + "'");

		this.mvc.perform(post("/api/v1/boards/" + this.job + "/cancellation").with(as(this.lan, Role.CUSTOMER)).with(csrf())
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"scope\":\"JOB\",\"reason\":\"Không còn cần nữa\",\"version\":" + version + "}"))
			.andExpect(status().isOk());
		this.mvc.perform(post("/api/v1/boards/" + this.job + "/disputes").with(as(this.lan, Role.CUSTOMER)).with(csrf())
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"reason\":\"Giai đoạn chưa đạt\"}"))
			.andExpect(status().is2xxSuccessful());
		awaitListeners();
	}

	/** FR-17, AD-14: before J6 the phase goes back to finding a Crew, and J6 waits; {@code FundingIT} covers the order. */
	@Test
	void theLastMemberLeavesBeforeJ6AndThePhaseGoesBackToFindingACrew() throws Exception {
		this.jdbc.sql("UPDATE job.job SET state = 'AGREEMENT' WHERE id = :id").param("id", this.job).update();
		leave(this.minh, null).andExpect(status().isNoContent());
		role(this.lan).andExpect(jsonPath("$.phase").value("AGREEING")).andExpect(jsonPath("$.noCrew").value(false));

		leave(this.vy, null).andExpect(status().isNoContent());
		awaitListeners();

		role(this.lan).andExpect(jsonPath("$.phase").value("FINDING_CREW")).andExpect(jsonPath("$.noCrew").value(false));
		role(this.hung).andExpect(jsonPath("$.phase").value("FINDING_CREW"));
		assertThat(notices("job.crew_empty", this.lan)).as("before J6 the Job simply looks for its Crew").isZero();
		assertThat(text("SELECT count(*)::text FROM notification.todo WHERE todo_key = 'matching.open_crew_recruitment'"
				+ " AND closed_at IS NULL")).isEqualTo("1");
	}

	// ---- A split party --------------------------------------------------------------------------

	/** SM-8, FR-75: a member who leaves mid-Milestone stays a party at its release, through Story 22.1's rule. */
	@Test
	void aMemberLeavingMidMilestoneIsAPartyNoLongerActive() throws Exception {
		leave(this.vy, null).andExpect(status().isNoContent());
		awaitListeners();
		this.clock.advance(Duration.ofHours(1));

		accept();

		assertThat(this.splits.splitPartiesOf(this.world.milestone())).containsExactly(new SplitParty(this.hung, true, true, true),
				new SplitParty(this.vy, false, false, false), new SplitParty(this.minh, false, true, true));
		assertThat(text("SELECT cap_applies::text FROM job.milestone_split")).isEqualTo("true");
	}

	@Test
	void whenEveryMemberLeftMidMilestoneTheCapDoesNotApply() throws Exception {
		leave(this.vy, null).andExpect(status().isNoContent());
		leave(this.minh, null).andExpect(status().isNoContent());
		awaitListeners();
		this.clock.advance(Duration.ofHours(1));

		accept();

		assertThat(this.splits.splitPartiesOf(this.world.milestone())).containsExactly(new SplitParty(this.hung, true, true, true),
				new SplitParty(this.vy, false, false, false), new SplitParty(this.minh, false, false, false));
		assertThat(text("SELECT cap_applies::text FROM job.milestone_split")).isEqualTo("false");
	}

	// ---- N-28 -----------------------------------------------------------------------------------

	/** R9: N-28 names the Held Milestones and those with a split not final where the caller is a party. */
	@Test
	void theLeaveNoticeNamesTheMilestonesTheMemberStaysAPartyOf() throws Exception {
		me(this.vy).andExpect(status().isOk())
			.andExpect(jsonPath("$.title").value("Sửa lỗi đặt bàn"))
			.andExpect(jsonPath("$.state").value("ACTIVE"))
			.andExpect(jsonPath("$.productName").value("Sổ đặt bàn Quán Mây"))
			.andExpect(jsonPath("$.mentorName").value("Đỗ Mạnh Hùng"))
			.andExpect(jsonPath("$.former").value(false))
			.andExpect(jsonPath("$.canLeave").value(true))
			.andExpect(jsonPath("$.codeJob").value(false))
			.andExpect(jsonPath("$.partyMilestones[0]").value(1));

		UUID second = UUID.randomUUID();
		this.jdbc.sql("""
				INSERT INTO job.milestone (id, job_id, position, title, price_vnd, revision_rounds, created_at, state, funded_at,
				    crew_terms_version_no, accepted_at)
				VALUES (:id, :job, 1, 'Giai đoạn 2', 500000, 2, now(), 'ACCEPTED', now(), 1, now())
				""").param("id", second).param("job", this.job).update();
		UUID split = UUID.randomUUID();
		this.jdbc.sql("""
				INSERT INTO job.milestone_split (id, job_id, product_id, milestone_id, milestone_number, mentor_account_id, status,
				    cap_applies, released_at, draft_due_at, created_at)
				VALUES (:id, :job, :product, :milestone, 2, :mentor, 'AWAITING_DRAFT', true, now(), now(), now())
				""").param("id", split).param("job", this.job).param("product", this.world.product()).param("milestone", second)
			.param("mentor", this.hung).update();
		this.jdbc.sql("""
				INSERT INTO job.milestone_split_party (id, split_id, party_account_id, is_mentor, whole_interval, active_at_release,
				    position)
				VALUES (:id, :split, :vy, false, true, true, 1)
				""").param("id", UUID.randomUUID()).param("split", split).param("vy", this.vy).update();
		me(this.vy).andExpect(jsonPath("$.partyMilestones.length()").value(2)).andExpect(jsonPath("$.partyMilestones[1]").value(2));
		me(this.minh).andExpect(jsonPath("$.partyMilestones.length()").value(1));

		this.jdbc.sql("UPDATE job.milestone SET state = 'ACCEPTED', accepted_at = now() WHERE id = :id")
			.param("id", this.world.milestone()).update();
		me(this.minh).andExpect(jsonPath("$.partyMilestones.length()").value(0));

		this.jdbc.sql("UPDATE job.job SET job_types = ARRAY['BUG_FIX'] WHERE id = :id").param("id", this.job).update();
		me(this.minh).andExpect(jsonPath("$.codeJob").value(true));
	}

	// ---- External Access ------------------------------------------------------------------------

	/** FR-66, R15: one revocation to-do per item, identifier given or not, ticked one by one, beside the rows of the end. */
	@Test
	void aLeaverGetsOneRevocationToDoPerItemTickedOneByOne() throws Exception {
		twoExternalAccessItems();
		String login = TeamTestSupport.login(this.jdbc, this.vy);
		identifier(SUPABASE, this.vy, "vy@quanmay.vn");

		leave(this.vy, null).andExpect(status().isNoContent());
		awaitListeners();

		assertThat(openTodos("repo.revocation_item_member", this.lan)).isEqualTo(2);
		assertThat(this.jdbc.sql("""
				SELECT params ->> 'system' || ' ' || (params ->> 'login') FROM notification.todo
				WHERE todo_key = 'repo.revocation_item_member' ORDER BY 1
				""").query(String.class).list()).containsExactly("Supabase " + login, "Vercel " + login);
		checklist(this.lan, Role.CUSTOMER).andExpect(status().isOk())
			.andExpect(jsonPath("$.items.length()").value(2))
			.andExpect(jsonPath("$.items[?(@.system == 'Supabase')].memberLogin").value(login))
			.andExpect(jsonPath("$.items[?(@.system == 'Supabase')].identifiers[0].identifier").value("vy@quanmay.vn"))
			.andExpect(jsonPath("$.items[?(@.system == 'Vercel')].identifiers.length()").value(0));
		checklist(this.hung, Role.MENTOR).andExpect(jsonPath("$.items.length()").value(0));

		confirmRevocation(SUPABASE, null).andExpect(status().isNotFound());
		confirmRevocation(SUPABASE, "khong-phai-ai").andExpect(status().isNotFound());
		confirmRevocation(SUPABASE, login).andExpect(status().isOk());
		assertThat(openTodos("repo.revocation_item_member", this.lan)).isOne();
		confirmRevocation(SUPABASE, login).andExpect(status().isOk());
		assertThat(openTodos("repo.revocation_item_member", this.lan)).isOne();
		confirmRevocation(VERCEL, login).andExpect(status().isOk());
		assertThat(openTodos("repo.revocation_item_member", this.lan)).isZero();
		assertThat(count("SELECT count(*) FROM repo.revocation_item WHERE status = 'CONFIRMED'")).isEqualTo(2);
	}

	/** R15: a later joiner's grant to-dos, closed by the tick or when they leave; the tick shows undone until then. */
	@Test
	void aLaterJoinerGetsOneGrantToDoPerItem() throws Exception {
		twoExternalAccessItems();
		this.transactions.executeWithoutResult(tx -> {
			for (UUID access : List.of(SUPABASE, VERCEL)) {
				this.todos.open(new TodoRequest(ExternalAccessGrants.TODO, this.lan, Audience.CUSTOMER, access,
						Map.of("system", "Dịch vụ"), "/cong-viec/" + this.job + "/thoa-thuan", null, this.job));
			}
		});
		grantTick(SUPABASE).andExpect(status().isOk());
		grantTick(VERCEL).andExpect(status().isOk());
		access(this.lan, Role.CUSTOMER).andExpect(jsonPath("$.items[0].granted").value(true));
		UUID an = JobContent.member(this.jdbc, "Phan Văn An");
		String login = TeamTestSupport.login(this.jdbc, an);

		joined(an);

		assertThat(openTodos("job.grant_external_access_member", this.lan)).isEqualTo(2);
		assertThat(text("""
				SELECT params ->> 'login' FROM notification.todo WHERE todo_key = 'job.grant_external_access_member' LIMIT 1
				""")).isEqualTo(login);
		access(this.lan, Role.CUSTOMER).andExpect(jsonPath("$.items[0].granted").value(false))
			.andExpect(jsonPath("$.items[1].granted").value(false));

		grantTick(SUPABASE).andExpect(status().isOk());
		assertThat(openTodos("job.grant_external_access_member", this.lan)).isOne();
		access(this.lan, Role.CUSTOMER).andExpect(jsonPath("$.items[0].granted").value(true))
			.andExpect(jsonPath("$.items[1].granted").value(false));

		leave(an, null).andExpect(status().isNoContent());
		assertThat(openTodos("job.grant_external_access_member", this.lan)).isZero();
		awaitListeners();
		assertThat(openTodos("repo.revocation_item_member", this.lan)).as("what was granted may stand").isEqualTo(2);
	}

	// ---- A former member ------------------------------------------------------------------------

	/** AD-41, FR-77: every tab reads read-only and only up to the end of their window; nothing is written any more. */
	@Test
	void aFormerMemberReadsOnlyTheirWindow() throws Exception {
		JobContent.board(this.jdbc, this.job, this.world.product(), "SDB");
		UUID mine = item("Việc của Vy", this.vy);
		UUID chat = JobContent.chat(this.jdbc, this.job, this.world.product(), "CREW");
		JobContent.message(this.jdbc, chat, this.hung, "Tin trong thời gian của Vy", this.clock.instant());
		JobContent.page(this.jdbc, this.job, this.hung, "Trang trong thời gian của Vy", "Nội dung", this.clock.instant());
		this.clock.advance(Duration.ofMinutes(1));
		leave(this.vy, null).andExpect(status().isNoContent());
		awaitListeners();
		this.clock.advance(Duration.ofHours(1));
		UUID later = item("Việc sau khi Vy rời", this.minh);
		JobContent.message(this.jdbc, chat, this.hung, "Tin sau khi Vy rời", this.clock.instant());
		JobContent.page(this.jdbc, this.job, this.hung, "Trang sau khi Vy rời", "Nội dung", this.clock.instant());

		this.mvc.perform(get("/api/v1/boards/" + this.job + "/work-items").with(as(this.vy, Role.STUDENT)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.readOnly").value(true))
			.andExpect(jsonPath("$.items.length()").value(1))
			.andExpect(jsonPath("$.items[0].id").value(mine.toString()));
		this.mvc.perform(get("/api/v1/work-items/" + later).with(as(this.vy, Role.STUDENT))).andExpect(status().isNotFound());
		assertThat(body("/api/v1/boards/" + this.job + "/crew-chat/messages", this.vy)).contains("Tin trong thời gian của Vy")
			.doesNotContain("Tin sau khi Vy rời");
		assertThat(body("/api/v1/boards/" + this.job + "/pages", this.vy)).contains("Trang trong thời gian của Vy")
			.doesNotContain("Trang sau khi Vy rời");
		this.mvc.perform(post("/api/v1/work-items/" + mine + "/move").with(as(this.vy, Role.STUDENT)).with(csrf())
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"to\":\"IN_PROGRESS\",\"version\":1}"))
			.andExpect(status().isNotFound());
		this.mvc.perform(post("/api/v1/crew-chats/" + chat + "/messages").with(as(this.vy, Role.STUDENT)).with(csrf())
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"body\":\"Mình vẫn còn đây\"}"))
			.andExpect(status().is4xxClientError());
		assertThat(count("SELECT count(*) FROM chat.message WHERE author_account_id = '" + this.vy + "'")).isZero();
	}

	/** FR-66, FR-77: before J7 their duties stay: JOB-1's header, the data-deletion checklist and its tick. */
	@ParameterizedTest
	@ValueSource(strings = { "AGREEMENT", "STARTING" })
	void aFormerMembersDutiesStayBeforeTheBoard(String state) throws Exception {
		this.jdbc.sql("UPDATE job.job SET state = :state WHERE id = :id").param("state", state).param("id", this.job).update();
		me(this.vy).andExpect(jsonPath("$.canLeave").value(true));
		leave(this.vy, null).andExpect(status().isNoContent());
		awaitListeners();

		assertThat(openTodos("repo.confirm_data_deletion", this.vy)).isOne();
		me(this.vy).andExpect(status().isOk()).andExpect(jsonPath("$.former").value(true)).andExpect(jsonPath("$.state").value(state));
		this.mvc.perform(get("/api/v1/boards/" + this.job + "/data-deletion").with(as(this.vy, Role.STUDENT)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.left").value(true))
			.andExpect(jsonPath("$.mine.length()").value(2));
		// Only a former member reads it before the Job has a Board.
		this.mvc.perform(get("/api/v1/boards/" + this.job + "/data-deletion").with(as(this.minh, Role.STUDENT)))
			.andExpect(status().isNotFound());
		this.mvc.perform(get("/api/v1/boards/" + this.job + "/data-deletion").with(as(this.hung, Role.MENTOR)))
			.andExpect(status().isNotFound());
		this.mvc.perform(get("/api/v1/boards/" + this.job + "/data-deletion").with(as(this.lan, Role.CUSTOMER)))
			.andExpect(status().isNotFound());
		for (String item : List.of("NON_CODE_DATA", "PREVIEWS")) {
			this.mvc.perform(post("/api/v1/boards/" + this.job + "/data-deletion/" + item).with(as(this.vy, Role.STUDENT)).with(csrf()))
				.andExpect(status().isOk());
		}
		assertThat(openTodos("repo.confirm_data_deletion", this.vy)).isZero();
		awaitListeners();
	}

	/** "· đã rời" reads the release's reason, never the Job's state: after the end too, and only for {@code LEFT}. */
	@Test
	void afterTheEndALeaverStillReadsLeftAndAMemberReleasedByTheEndDoesNot() throws Exception {
		leave(this.vy, null).andExpect(status().isNoContent());
		awaitListeners();
		role(this.vy).andExpect(jsonPath("$.left").value(true));
		role(this.minh).andExpect(jsonPath("$.left").value(false)).andExpect(jsonPath("$.crewKind").value("ACTIVE"));

		this.jdbc.sql("UPDATE job.job SET state = 'CLOSED', closed_at = now() WHERE id = :id").param("id", this.job).update();
		this.jdbc.sql("""
				UPDATE job.crew_row SET status = 'RELEASED', released_reason = 'JOB_ENDED', released_at = now(), window_end = now()
				WHERE member_account_id = :id
				""").param("id", this.minh).update();

		role(this.vy).andExpect(jsonPath("$.left").value(true)).andExpect(jsonPath("$.crewKind").value("FORMER"));
		role(this.minh).andExpect(jsonPath("$.left").value(false)).andExpect(jsonPath("$.crewKind").value("FORMER"));
		this.mvc.perform(get("/api/v1/my-jobs").with(as(this.vy, Role.STUDENT))).andExpect(jsonPath("$[0].left").value(true));
		this.mvc.perform(get("/api/v1/my-jobs").with(as(this.minh, Role.STUDENT))).andExpect(jsonPath("$[0].left").value(false));
	}

	// ---- Erasure --------------------------------------------------------------------------------

	/** AD-24: the reason goes to its author in their export; an erasure clears it and anonymizes the revocation rows. */
	@Test
	void anErasureClearsTheReasonAndAnonymizesTheRevocationRows() throws Exception {
		twoExternalAccessItems();
		identifier(SUPABASE, this.vy, "vy@quanmay.vn");
		leave(this.vy, REASON).andExpect(status().isNoContent());
		awaitListeners();
		this.jdbc.sql("UPDATE job.milestone SET state = 'CANCELLED' WHERE id = :id").param("id", this.world.milestone()).update();
		assertThat(exported(this.vy)).contains(REASON).contains("revocationItems").contains("Supabase");
		assertThat(exported(this.minh)).doesNotContain(REASON);

		ErasureOutcome outcome = this.transactions.execute(status -> this.personalData.erase(this.vy));
		assertThat(outcome).isInstanceOf(ErasureOutcome.Erased.class);

		assertThat(count("SELECT count(*) FROM job.crew_row WHERE left_note IS NOT NULL")).isZero();
		assertThat(count("SELECT count(*) FROM repo.revocation_item")).as("the rows stay").isEqualTo(2);
		assertThat(count("""
				SELECT count(*) FROM repo.revocation_item WHERE member_account_id IS NOT NULL OR member_login IS NOT NULL
				    OR identifiers <> '[]'
				""")).isZero();
		assertThat(openTodos("repo.revocation_item_member", this.lan)).as("nobody is left to name").isZero();
		checklist(this.lan, Role.CUSTOMER).andExpect(jsonPath("$.items.length()").value(0));
	}

	/** The retention run (NFR-10): the reason goes, and with the rows it removes, their to-dos. */
	@Test
	void theRetentionRunClearsTheReasonAndClosesTheToDosOfTheRowsItRemoves() throws Exception {
		twoExternalAccessItems();
		leave(this.vy, REASON).andExpect(status().isNoContent());
		awaitListeners();
		end("J8");
		awaitListeners();
		assertThat(openTodos("repo.revocation_item_member", this.lan)).isEqualTo(2);
		assertThat(openTodos("repo.revocation_item", this.lan)).isEqualTo(2);

		this.transactions.executeWithoutResult(status -> this.personalData.retireJobData(this.world.product()));

		assertThat(count("SELECT count(*) FROM job.crew_row WHERE left_note IS NOT NULL")).isZero();
		assertThat(count("SELECT count(*) FROM repo.revocation_item")).isZero();
		assertThat(openTodos("repo.revocation_item_member", this.lan)).isZero();
		assertThat(openTodos("repo.revocation_item", this.lan)).isZero();
	}

	@Test
	void aProductDeletionClosesTheToDosOfTheRowsItRemoves() throws Exception {
		twoExternalAccessItems();
		leave(this.vy, null).andExpect(status().isNoContent());
		awaitListeners();
		end("J8");
		awaitListeners();

		this.transactions.executeWithoutResult(status -> this.personalData.deleteProduct(this.world.product()));

		assertThat(count("SELECT count(*) FROM repo.revocation_item")).isZero();
		assertThat(openTodos("repo.revocation_item_member", this.lan)).isZero();
		assertThat(openTodos("repo.revocation_item", this.lan)).isZero();
	}

	/** The CHECK holds on a row not released, and on one released otherwise (the unreleased case is NULL, not false). */
	@Test
	void onlyARowReleasedAsLeftHoldsAReason() {
		String note = "UPDATE job.crew_row SET left_note = 'Lý do' WHERE member_account_id = :id";
		assertThatThrownBy(() -> this.jdbc.sql(note).param("id", this.vy).update())
			.isInstanceOf(DataIntegrityViolationException.class)
			.hasMessageContaining("crew_row_left_note_check");
		this.jdbc.sql("""
				UPDATE job.crew_row SET status = 'RELEASED', released_reason = 'JOB_ENDED', released_at = now(), window_end = now()
				WHERE member_account_id = :id
				""").param("id", this.minh).update();
		assertThatThrownBy(() -> this.jdbc.sql(note).param("id", this.minh).update())
			.isInstanceOf(DataIntegrityViolationException.class)
			.hasMessageContaining("crew_row_left_note_check");
	}

	// ---- The Job's end and the Revocation Checklist ----------------------------------------------

	/** R15: a member who signed later has grant to-dos, which the Job's end closes, the items ticked or not. */
	@ParameterizedTest
	@ValueSource(strings = { "J8", "J10" })
	void theJobsEndClosesEveryMembersGrantToDo(String end) throws Exception {
		twoExternalAccessItems();
		joined(JobContent.member(this.jdbc, "Phan Văn An"));
		assertThat(openTodos("job.grant_external_access_member", this.lan)).isEqualTo(2);

		end(end);

		assertThat(openTodos("job.grant_external_access_member", this.lan)).isZero();
		awaitListeners();
	}

	/** FR-66: days 1 and 7 after each leave, beside each other and beside J8's, each counting what is pending then. */
	@Test
	void aLeaversRowsAreRemindedOnDaysOneAndSevenAndNoReminderSwallowsAnother() throws Exception {
		twoExternalAccessItems();
		leave(this.vy, null).andExpect(status().isNoContent());
		awaitListeners();
		assertThat(count("SELECT count(*) FROM timers.deadline WHERE kind = 'repo.revocation_checklist_reminder'")).isEqualTo(2);

		nextDay();
		assertThat(reminders()).containsExactly("2");

		leave(this.minh, null).andExpect(status().isNoContent());
		awaitListeners();
		confirmRevocation(SUPABASE, TeamTestSupport.login(this.jdbc, this.vy)).andExpect(status().isOk());
		nextDay();
		assertThat(reminders()).containsExactlyInAnyOrder("2", "3");

		end("J8");
		awaitListeners();
		nextDay();
		assertThat(reminders()).containsExactlyInAnyOrder("2", "3", "5");
		assertThat(count("SELECT count(*) FROM timers.deadline WHERE kind = 'repo.revocation_checklist_reminder'")).isEqualTo(6);

		this.clock.advance(Duration.ofDays(4));
		this.poller.runDue();
		assertThat(reminders()).as("Vy's day 7").containsExactlyInAnyOrder("2", "3", "5", "5");
	}

	/** R15: two leavers and the Job's end on one item are three rows, each ticked on its own, and audited as its own. */
	@Test
	void eachTickConfirmsItsOwnRowBesideTheRowsOfTheJobsEnd() throws Exception {
		twoExternalAccessItems();
		String vyLogin = TeamTestSupport.login(this.jdbc, this.vy);
		String minhLogin = TeamTestSupport.login(this.jdbc, this.minh);
		leave(this.vy, null).andExpect(status().isNoContent());
		leave(this.minh, null).andExpect(status().isNoContent());
		awaitListeners();
		end("J8");
		awaitListeners();
		assertThat(count("SELECT count(*) FROM repo.revocation_item")).isEqualTo(6);
		assertThat(openTodos("repo.revocation_item_member", this.lan)).isEqualTo(4);
		assertThat(openTodos("repo.revocation_item", this.lan)).isEqualTo(2);
		UUID vyRow = this.jdbc.sql("SELECT id FROM job.crew_row WHERE member_account_id = :id").param("id", this.vy)
			.query(UUID.class).single();
		UUID vySubject = UUID.nameUUIDFromBytes((SUPABASE + ":revocation:" + vyRow).getBytes(StandardCharsets.UTF_8));
		assertThat(audited("repo.revocation_item_opened", vySubject)).isOne();

		confirmRevocation(SUPABASE, vyLogin).andExpect(status().isOk());

		assertThat(this.jdbc.sql("""
				SELECT coalesce(member_login, 'END') FROM repo.revocation_item WHERE access_id = :access AND status = 'CONFIRMED'
				""").param("access", SUPABASE).query(String.class).list()).containsExactly(vyLogin);
		assertThat(openTodos("repo.revocation_item_member", this.lan)).isEqualTo(3);
		assertThat(openTodos("repo.revocation_item", this.lan)).isEqualTo(2);
		assertThat(audited("repo.revocation_item_confirmed", vySubject)).isOne();
		assertThat(audited("repo.revocation_item_confirmed", SUPABASE)).as("the row of the end is another").isZero();

		confirmRevocation(SUPABASE, null).andExpect(status().isOk());

		assertThat(this.jdbc.sql("""
				SELECT coalesce(member_login, 'END') FROM repo.revocation_item WHERE access_id = :access AND status = 'CONFIRMED'
				""").param("access", SUPABASE).query(String.class).list()).containsExactlyInAnyOrder(vyLogin, "END");
		assertThat(openTodos("repo.revocation_item_member", this.lan)).isEqualTo(3);
		assertThat(openTodos("repo.revocation_item", this.lan)).isOne();
		assertThat(audited("repo.revocation_item_confirmed", SUPABASE)).isOne();
		assertThat(count("SELECT count(*) FROM repo.revocation_item WHERE status = 'PENDING' AND member_login = '" + minhLogin + "'"))
			.isEqualTo(2);

		nextDay();
		assertThat(reminders()).as("three reminders fall due, each counting what is pending").containsOnly("4");
	}

	/** FR-66: before the Board too, a leaver gets their rows once the Agreement is confirmed. */
	@ParameterizedTest
	@ValueSource(strings = { "AGREEMENT", "STARTING" })
	void aLeaverBeforeTheBoardGetsTheirRevocationRows(String state) throws Exception {
		this.jdbc.sql("UPDATE job.job SET state = :state WHERE id = :id").param("state", state).param("id", this.job).update();
		twoExternalAccessItems();

		leave(this.vy, null).andExpect(status().isNoContent());
		awaitListeners();

		assertThat(openTodos("repo.revocation_item_member", this.lan)).isEqualTo(2);
		checklist(this.lan, Role.CUSTOMER).andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(2));
	}

	/** A duty the leaver finished before the end: J10 opens nothing and tells nothing again. */
	@Test
	void aDutyCompleteBeforeTheEndGetsNoNewToDoAndNoSecondNotice() throws Exception {
		leave(this.vy, null).andExpect(status().isNoContent());
		awaitListeners();
		for (String item : List.of("NON_CODE_DATA", "PREVIEWS")) {
			this.mvc.perform(post("/api/v1/boards/" + this.job + "/data-deletion/" + item).with(as(this.vy, Role.STUDENT)).with(csrf()))
				.andExpect(status().isOk());
		}
		assertThat(openTodos("repo.confirm_data_deletion", this.vy)).isZero();

		end("J10");
		awaitListeners();

		assertThat(openTodos("repo.confirm_data_deletion", this.vy)).isZero();
		assertThat(count("SELECT count(*) FROM notification.todo WHERE todo_key = 'repo.confirm_data_deletion' AND account_id = '"
				+ this.vy + "'")).isOne();
		assertThat(notices("repo.data_deletion_confirmation_pending", this.vy)).isOne();
		assertThat(openTodos("repo.confirm_data_deletion", this.minh)).as("the end released Minh").isOne();
	}

	// ---- The Mentor's line ----------------------------------------------------------------------

	/** The line names who left work behind: not what the Mentor took back, and only while it can be assigned. */
	@Test
	void theLineNamesOnlyWhoLeftWorkBehindAndOnlyWhileItCanBeAssigned() throws Exception {
		JobContent.board(this.jdbc, this.job, this.world.product(), "SDB");
		UUID takenBack = item("Việc Hùng lấy lại", this.vy);
		assign(takenBack, null).andExpect(status().isOk());
		leave(this.vy, null).andExpect(status().isNoContent());
		awaitListeners();
		leavers().andExpect(jsonPath("$.leavers.length()").value(0));

		item("Việc của Minh", this.minh);
		leave(this.minh, null).andExpect(status().isNoContent());
		awaitListeners();
		leavers().andExpect(jsonPath("$.leavers.length()").value(1)).andExpect(jsonPath("$.leavers[0].name").value("Nguyễn Văn Minh"));

		this.jdbc.sql("UPDATE job.job SET state = 'CLOSING', closing_at = now() WHERE id = :id").param("id", this.job).update();
		leavers().andExpect(jsonPath("$.leavers.length()").value(0));
	}

	/** A person with neither a name nor a GitHub username reads as {@code CrewDepartures.shownName} calls them. */
	@Test
	void aLeaverErasedSinceReadsAsAMemberWithNoName() throws Exception {
		JobContent.board(this.jdbc, this.job, this.world.product(), "SDB");
		item("Việc của Vy", this.vy);
		leave(this.vy, null).andExpect(status().isNoContent());
		awaitListeners();
		this.jdbc.sql("UPDATE job.milestone SET state = 'CANCELLED' WHERE id = :id").param("id", this.world.milestone()).update();
		ErasureOutcome outcome = this.transactions.execute(status -> this.personalData.erase(this.vy));
		assertThat(outcome).isInstanceOf(ErasureOutcome.Erased.class);

		leavers().andExpect(jsonPath("$.leavers[0].name").value("Một thành viên nhóm"));
	}

	// ---- Helpers --------------------------------------------------------------------------------

	private ResultActions leavers() throws Exception {
		return this.mvc.perform(get("/api/v1/boards/" + this.job + "/progress").with(as(this.hung, Role.MENTOR))).andExpect(status().isOk());
	}

	/** J8 or J10 as a cancellation's cascade applies them, whatever Milestone is still Held. */
	private void end(String end) {
		long version = count("SELECT version FROM job.job WHERE id = '" + this.job + "'");
		assertThat("J8".equals(end)
				? this.lifecycle.tryApply(Transition.J8, this.job, Actor.SYSTEM, Trigger.AUTO, version, Map.of(Cancellations.INPUT, true))
				: this.lifecycle.tryApply(Transition.J10, this.job, Actor.user(this.lan), Trigger.USER, version))
			.isPresent();
	}

	private void nextDay() {
		this.clock.advance(Duration.ofDays(1).plusMinutes(1));
		this.poller.runDue();
	}

	private List<String> reminders() {
		return this.jdbc.sql("""
				SELECT params ->> 'n' FROM notification.notification WHERE type = 'repo.revocation_checklist_reminder'
				    AND account_id = :id
				""").param("id", this.lan).query(String.class).list();
	}

	private long audited(String action, UUID object) {
		return this.jdbc.sql("""
				SELECT count(*) FROM audit.audit_entry e JOIN audit.audit_entry_object o ON o.entry_id = e.id
				WHERE e.action = :action AND o.object_id = :object
				""").param("action", action).param("object", object).query(Long.class).single();
	}

	private ResultActions leave(UUID member, @Nullable String reason) throws Exception {
		return this.mvc.perform(post("/api/v1/jobs/" + this.job + "/crew/leave").with(as(member, Role.STUDENT)).with(csrf())
			.contentType(MediaType.APPLICATION_JSON)
			.content(reason == null ? "{}" : "{\"reason\":\"" + reason + "\"}"));
	}

	private ResultActions me(UUID account) throws Exception {
		return this.mvc.perform(get("/api/v1/jobs/" + this.job + "/crew/me").with(as(account, Role.STUDENT)));
	}

	private ResultActions role(UUID account) throws Exception {
		return this.mvc.perform(get("/api/v1/jobs/" + this.job + "/role").with(as(account, Role.STUDENT))).andExpect(status().isOk());
	}

	private ResultActions checklist(UUID account, Role role) throws Exception {
		return this.mvc.perform(get("/api/v1/boards/" + this.job + "/revocation-checklist").with(as(account, role)));
	}

	private ResultActions confirmRevocation(UUID access, @Nullable String login) throws Exception {
		return this.mvc.perform(post("/api/v1/boards/" + this.job + "/revocation-checklist/" + access).with(as(this.lan, Role.CUSTOMER))
			.with(csrf())
			.contentType(MediaType.APPLICATION_JSON)
			.content(login == null ? "{\"outcome\":\"REMOVED\"}" : "{\"outcome\":\"ROTATED\",\"memberLogin\":\"" + login + "\"}"));
	}

	private ResultActions access(UUID account, Role role) throws Exception {
		return this.mvc.perform(get("/api/v1/jobs/" + this.job + "/external-access").with(as(account, role))).andExpect(status().isOk());
	}

	private ResultActions grantTick(UUID access) throws Exception {
		return this.mvc.perform(post("/api/v1/jobs/" + this.job + "/external-access/" + access + "/granted")
			.with(as(this.lan, Role.CUSTOMER)).with(csrf()));
	}

	private String exported(UUID account) {
		List<PersonalDataSection> sections = this.transactions.execute(status -> this.personalData.export(account));
		return String.valueOf(sections);
	}

	private String body(String read, UUID account) throws Exception {
		return this.mvc.perform(get(read).with(as(account, Role.STUDENT)))
			.andExpect(status().isOk())
			.andReturn().getResponse().getContentAsString();
	}

	/** A Work Item the Mentor creates, assigned to {@code assignee}. */
	private UUID item(String title, UUID assignee) throws Exception {
		String created = this.mvc.perform(post("/api/v1/boards/" + this.job + "/work-items").with(as(this.hung, Role.MENTOR)).with(csrf())
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"type\":\"TASK\",\"title\":\"%s\",\"milestoneId\":\"%s\",\"assigneeId\":\"%s\"}"
				.formatted(title, this.world.milestone(), assignee)))
			.andExpect(status().isCreated())
			.andReturn().getResponse().getContentAsString();
		return UUID.fromString(JsonPath.read(created, "$.item.id"));
	}

	private ResultActions assign(UUID item, @Nullable UUID assignee) throws Exception {
		String title = text("SELECT title FROM work.work_item WHERE id = '" + item + "'");
		long version = count("SELECT version FROM work.work_item WHERE id = '" + item + "'");
		return this.mvc.perform(put("/api/v1/work-items/" + item)
			.with(as(this.hung, Role.MENTOR)).with(csrf())
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"type\":\"TASK\",\"title\":\"%s\",\"milestoneId\":\"%s\",\"assigneeId\":%s,\"version\":%d}"
				.formatted(title, this.world.milestone(), assignee == null ? "null" : "\"" + assignee + "\"", version)));
	}

	private void setStatus(UUID item, String status) {
		this.jdbc.sql("UPDATE work.work_item SET status = :status WHERE id = :id").param("status", status).param("id", item).update();
	}

	private @Nullable UUID assignee(UUID item) {
		return this.jdbc.sql("SELECT assignee_account_id FROM work.work_item WHERE id = :id").param("id", item)
			.query((rs, n) -> rs.getObject(1, UUID.class)).single();
	}

	/** The member's Work Item on another Job's Board, which a leave leaves alone. */
	private UUID otherJobsItem(UUID member) {
		UUID other = JobContent.activeJob(this.jdbc, this.world.product(), this.lan, this.hung, "Việc khác");
		JobContent.board(this.jdbc, other, this.world.product(), "VKH");
		UUID item = JobContent.workItem(this.jdbc, other, null, null, this.clock.instant());
		this.jdbc.sql("UPDATE work.work_item SET assignee_account_id = :member WHERE id = :id").param("member", member).param("id", item)
			.update();
		return item;
	}

	/** The member's write access to the Working Repository, read back (AD-11). */
	private UUID grant(UUID member) {
		UUID id = UUID.randomUUID();
		this.jdbc.sql("""
				INSERT INTO repo.job_grant (id, job_id, product_id, account_id, role, github_login, repository_github_id,
				    repository_name, crew_change, status, accepted_at, write_at, created_at)
				VALUES (:id, :job, :product, :member, 'CREW', :login, 4242, 'vibefix/job-working', false, 'WRITE', now(), now(), now())
				""").param("id", id).param("job", this.job).param("product", this.world.product()).param("member", member)
			.param("login", TeamTestSupport.login(this.jdbc, member)).update();
		return id;
	}

	/** Supabase and Vercel on the confirmed Agreement (FR-16). */
	private void twoExternalAccessItems() throws SQLException {
		String items = """
				[{"id":"%s","system":"Supabase","purpose":"Xem bảng đặt bàn","environment":"NON_PRODUCTION","accessLevel":"READ"},
				 {"id":"%s","system":"Vercel","purpose":"Xem bản thử","environment":"PRODUCTION","accessLevel":"WRITE"}]
				""".formatted(SUPABASE, VERCEL);
		// A confirmed Agreement is immutable to the application (AD-29), so the fixture writes it as the migration role would.
		try (Connection connection = database.superuserConnection(PostgresTestDatabase.DATABASE);
				PreparedStatement statement = connection.prepareStatement("""
						UPDATE job.confirmed_agreement SET content = jsonb_set(content, '{externalAccess}', CAST(? AS JSONB))
						WHERE job_id = ?
						""")) {
			statement.setString(1, items);
			statement.setObject(2, this.job);
			statement.executeUpdate();
		}
		int position = 0;
		for (UUID access : List.of(SUPABASE, VERCEL)) {
			this.jdbc.sql("""
					INSERT INTO job.external_access (id, job_id, position, system, purpose, created_at)
					VALUES (:id, :job, :position, 'Dịch vụ', 'Xem', now())
					""").param("id", access).param("job", this.job).param("position", position++).update();
		}
	}

	private void identifier(UUID access, UUID account, String identifier) {
		this.jdbc.sql("""
				INSERT INTO job.external_access_identifier (access_id, account_id, job_id, identifier, updated_at)
				VALUES (:access, :account, :job, :identifier, now())
				""").param("access", access).param("account", account).param("job", this.job).param("identifier", identifier).update();
	}

	/** The person's pick and signature, as {@code matching} calls {@code job}. */
	private void joined(UUID person) {
		this.transactions.executeWithoutResult(tx -> {
			CrewPicks.Pick pick = this.picks.addCrew(this.hung, this.job, UUID.randomUUID(), person, Contribution.NONE);
			this.picks.activateCrew(this.job, pick.rowId(), Contribution.NONE, this.clock.instant());
		});
		awaitListeners();
	}

	/** M5 as {@code delivery} applies it on an Assessment Job, then the pool, in one transaction. */
	private void accept() {
		UUID milestone = this.world.milestone();
		this.transactions.executeWithoutResult(tx -> {
			UUID handover = UUID.randomUUID();
			this.jdbc.sql("UPDATE job.milestone SET handover_id = :handover, state = 'SUBMITTED', submitted_at = :now WHERE id = :id")
				.param("handover", handover)
				.param("now", Timestamp.from(this.clock.instant()))
				.param("id", milestone)
				.update();
			UUID transition = this.handovers.autoAccept(this.job, milestone, handover).orElseThrow().transitionId();
			this.releases.release(this.job, milestone, transition);
		});
		awaitListeners();
	}

	private long notices(String type, UUID account) {
		return this.jdbc.sql("SELECT count(*) FROM notification.notification WHERE type = :type AND account_id = :account")
			.param("type", type).param("account", account).query(Long.class).single();
	}

	private long openTodos(String key, UUID account) {
		return this.jdbc.sql("""
				SELECT count(*) FROM notification.todo WHERE todo_key = :key AND account_id = :account AND closed_at IS NULL
				""").param("key", key).param("account", account).query(Long.class).single();
	}

	private void awaitListeners() {
		await().atMost(Duration.ofSeconds(15))
			.until(() -> count("SELECT count(*) FROM platform.event_publication WHERE completion_date IS NULL") == 0);
	}

	private long count(String sql) {
		return this.jdbc.sql(sql).query(Long.class).single();
	}

	private String text(String sql) {
		return this.jdbc.sql(sql).query(String.class).single();
	}

	private RequestPostProcessor as(UUID account, Role role) {
		return IdentityTestSupport.signedIn(this.sessions, account, role, this.clock.instant());
	}

}
