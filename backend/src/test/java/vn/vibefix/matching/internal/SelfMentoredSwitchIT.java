package vn.vibefix.matching.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.session.Session;
import org.springframework.session.SessionRepository;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import vn.vibefix.identity.internal.IdentityTestSupport;
import vn.vibefix.identity.internal.Role;
import vn.vibefix.job.JobRole;
import vn.vibefix.job.JobRoles;
import vn.vibefix.job.JobSelection;
import vn.vibefix.support.IntegrationTest;
import vn.vibefix.support.MutableClock;
import vn.vibefix.support.TestPeople;
import vn.vibefix.timers.internal.DeadlinePoller;

/**
 * FR-85, AD-47, AD-4 (J12), AD-36, B-18, Story 25.2b: with {@code features.selfMentoredJobs} on, the Customer of an Open
 * Job nobody bids on switches it to self-mentored. It enters Agreement as J11's Job does, its Invitations to Apply
 * lapse, its selection period ends, and a bid that raced the switch is withdrawn. The switch is one-way.
 */
@TestPropertySource(properties = "vibefix.features.self-mentored-jobs=true")
class SelfMentoredSwitchIT extends IntegrationTest {

	private static final String NOTICE = "1.0";

	@Autowired
	private MockMvc mvc;

	@Autowired
	private JdbcClient jdbc;

	@Autowired
	private SessionRepository<? extends Session> sessions;

	@Autowired
	private JobRoles roles;

	@Autowired
	private JobSelection selection;

	@Autowired
	private SelfMentoredBids bids;

	@Autowired
	private DeadlinePoller poller;

	private UUID lan;

	private UUID product;

	@BeforeEach
	void aCustomerWithAProduct() {
		this.lan = IdentityTestSupport.insertAccount(this.jdbc, Role.CUSTOMER, null);
		this.jdbc.sql("""
				INSERT INTO identity.profile (account_id, kind, display_name, contact_email, updated_at)
				VALUES (:id, 'INDIVIDUAL', 'Nguyễn Thị Lan', 'lan@example.com', now())
				""").param("id", this.lan).update();
		this.product = product(this.lan);
	}

	@Test
	void switchingAnOpenJobNobodyBidsOnEntersAgreementWithItsCustomerAsItsMentor() throws Exception {
		UUID job = publishedJob();
		Instant published = this.clock.instant();
		UUID hung = mentor("Đỗ Mạnh Hùng");
		this.mvc.perform(post("/api/v1/jobs/" + job + "/invitations").with(as(this.lan)).with(csrf())
			.contentType(MediaType.APPLICATION_JSON).content("{\"mentorId\":\"" + hung + "\"}")).andExpect(status().isCreated());
		this.clock.advance(Duration.ofHours(1));
		Instant now = this.clock.instant();
		Instant due = now.plus(Duration.ofDays(14));

		switchIt(job, 1, NOTICE).andExpect(status().isOk()).andExpect(jsonPath("$.version").value(2));

		assertThat(this.jdbc.sql("""
				SELECT state || ' ' || self_mentored || ' ' || (mentor_account_id = customer_account_id) || ' '
				    || (selected_application_id IS NULL) || ' ' || (selected_at = :now) || ' ' || (agreement_due_at = :due)
				    || ' ' || (quote_price_vnd IS NULL AND quote_timeline_days IS NULL) || ' ' || self_mentored_notice_version
				    || ' ' || (opened_at = :published) || ' ' || (rules ->> 'AGREEMENT_STALL_DAYS')
				FROM job.job WHERE id = :id
				""").param("id", job).param("now", Timestamp.from(now)).param("due", Timestamp.from(due))
			.param("published", Timestamp.from(published)).query(String.class).single())
			.as("J12 records the Customer as the Mentor and keeps the rules J1 copied")
			.isEqualTo("AGREEMENT true true true true true true 1.0 true 14");
		assertThat(this.roles.roleOf(job, this.lan)).isEqualTo(JobRole.CUSTOMER_MENTOR);
		assertThat(this.jdbc.sql("SELECT product_id FROM product.job_pin WHERE job_id = :id").param("id", job)
			.query(UUID.class).list()).as("the Product is pinned while the Job runs").containsExactly(this.product);
		assertThat(this.jdbc.sql("""
				SELECT count(*) FROM audit.audit_entry
				WHERE action = 'job.transition_j12' AND job_id = :id AND actor_account_id = :lan
				""").param("id", job).param("lan", this.lan).query(Long.class).single()).isOne();
		// Drafting starts: the draft exists and its to-do is the Customer's.
		assertThat(count("SELECT count(*) FROM job.agreement_draft WHERE job_id = :id", job)).isOne();
		assertThat(this.jdbc.sql("""
				SELECT account_id FROM notification.todo WHERE todo_key = 'job.draft_agreement' AND job_id = :id AND closed_at IS NULL
				""").param("id", job).query(UUID.class).single()).isEqualTo(this.lan);

		// The listeners: the Crew Chat alone, the Agreement stall, the recruitment to-do, and the invitation's lapse.
		await().atMost(Duration.ofSeconds(10)).until(() -> count("SELECT count(*) FROM chat.chat WHERE job_id = :id", job) == 1
				&& count("SELECT count(*) FROM timers.deadline WHERE job_id = :id AND kind = 'job.agreement_deadline'", job) == 1
				&& count("""
						SELECT count(*) FROM notification.todo WHERE todo_key = 'matching.open_crew_recruitment' AND job_id = :id
						""", job) == 1
				&& count("SELECT count(*) FROM matching.invitation WHERE job_id = :id AND status = 'LAPSED'", job) == 1);
		assertThat(this.jdbc.sql("SELECT kind FROM chat.chat WHERE job_id = :id").param("id", job).query(String.class).list())
			.as("a Self-mentored Job never has a Job Chat").containsExactly("CREW");
		assertThat(this.jdbc.sql("""
				SELECT account_id FROM notification.todo WHERE todo_key = 'matching.open_crew_recruitment' AND job_id = :id
				""").param("id", job).query(UUID.class).single()).isEqualTo(this.lan);
		assertThat(notifications("job.switched_to_self_mentored", hung)).as("the invited person learns why").isOne();
		assertThat(this.jdbc.sql("""
				SELECT count(*) FROM notification.todo
				WHERE todo_key = 'matching.answer_invitation_to_apply' AND account_id = :hung AND closed_at IS NULL
				""").param("hung", hung).query(Long.class).single()).isZero();
		this.mvc.perform(get("/api/v1/jobs/" + job).with(as(this.lan))).andExpect(status().isOk())
			.andExpect(jsonPath("$.state").value("AGREEMENT"))
			.andExpect(jsonPath("$.selfMentored").value(true))
			.andExpect(jsonPath("$.mentor").doesNotExist());
		this.mvc.perform(get("/api/v1/jobs/" + job + "/role").with(as(this.lan))).andExpect(status().isOk())
			.andExpect(jsonPath("$.role").value("CUSTOMER_MENTOR"));

		// The selection period ends by itself: its Deadline finds the Job no longer Open.
		this.clock.advance(Duration.ofDays(14).minusMinutes(30));
		this.poller.runDue();
		assertThat(state(job)).isEqualTo("AGREEMENT");
		assertThat(this.jdbc.sql("""
				SELECT status FROM timers.deadline WHERE kind = 'job.selection_lapse' AND subject_id = :id
				""").param("id", job).query(String.class).single()).isEqualTo("OBSOLETE");

		// One-way: nothing switches it again, publishes it, withdraws it or edits its mode.
		switchIt(job, 2, NOTICE).andExpect(status().isConflict()).andExpect(jsonPath("$.reason").value("STATE_CHANGED"));
		this.mvc.perform(post("/api/v1/jobs/" + job + "/publish").with(as(this.lan)).with(csrf()))
			.andExpect(status().isConflict()).andExpect(jsonPath("$.reason").value("STATE_CHANGED"));
		this.mvc.perform(post("/api/v1/jobs/" + job + "/withdraw").with(as(this.lan)).with(csrf()))
			.andExpect(status().isConflict());
		this.mvc.perform(put("/api/v1/jobs/" + job).with(as(this.lan)).with(csrf()).contentType(MediaType.APPLICATION_JSON)
			.content(body())).andExpect(status().isConflict()).andExpect(jsonPath("$.reason").value("JOB_NOT_DRAFT"));
		assertThat(this.jdbc.sql("SELECT self_mentored FROM job.job WHERE id = :id").param("id", job).query(Boolean.class).single())
			.isTrue();
	}

	/**
	 * The Job was Selecting once and its selected Mentor declined (J3, then J5): it is Open again, with that bid's quote,
	 * a second selection period and no pin. The switch leaves nothing of the selection behind.
	 */
	@Test
	void aJobWhoseSelectedMentorDeclinedIsSwitchedWithNothingOfTheSelectionLeft() throws Exception {
		UUID job = publishedJob();
		UUID hung = mentor("Đỗ Mạnh Hùng");
		UUID bid = application(job, hung);
		this.mvc.perform(post("/api/v1/jobs/" + job + "/applications/" + bid + "/select").with(as(this.lan)).with(csrf())
			.contentType(MediaType.APPLICATION_JSON).content("{\"version\":1}")).andExpect(status().isOk());
		await().atMost(Duration.ofSeconds(10)).until(() -> "SELECTED".equals(applicationStatus(bid)));
		this.clock.advance(Duration.ofHours(1));
		this.mvc.perform(post("/api/v1/mentor/jobs/" + job + "/decline").with(as(hung)).with(csrf())
			.contentType(MediaType.APPLICATION_JSON).content("{\"version\":2}")).andExpect(status().isNoContent());
		await().atMost(Duration.ofSeconds(10)).until(() -> "CLOSED".equals(applicationStatus(bid)));
		assertThat(this.jdbc.sql("""
				SELECT state || ' ' || quote_price_vnd || ' ' || (mentor_account_id IS NULL) FROM job.job WHERE id = :id
				""").param("id", job).query(String.class).single()).as("J5 keeps the declined bid's quote")
			.isEqualTo("OPEN 800000 true");
		assertThat(count("SELECT count(*) FROM product.job_pin WHERE job_id = :id", job)).as("J5 released the pin").isZero();
		this.clock.advance(Duration.ofHours(1));

		switchIt(job, 3, NOTICE).andExpect(status().isOk()).andExpect(jsonPath("$.version").value(4));

		assertThat(this.jdbc.sql("""
				SELECT state || ' ' || (mentor_account_id = customer_account_id) || ' ' || (selected_application_id IS NULL)
				    || ' ' || (quote_price_vnd IS NULL AND quote_timeline_days IS NULL)
				FROM job.job WHERE id = :id
				""").param("id", job).query(String.class).single()).isEqualTo("AGREEMENT true true true");
		assertThat(this.roles.roleOf(job, this.lan)).isEqualTo(JobRole.CUSTOMER_MENTOR);
		assertThat(this.roles.roleOf(job, hung)).as("the Mentor who declined holds no role").isEqualTo(JobRole.NONE);
		assertThat(count("SELECT count(*) FROM product.job_pin WHERE job_id = :id", job)).as("J12 pins the Product again").isOne();
		assertThat(applicationStatus(bid)).as("a closed bid stays closed").isEqualTo("CLOSED");
		this.mvc.perform(get("/api/v1/mentor/jobs/" + job + "/agreement").with(as(this.lan))).andExpect(status().isOk())
			.andExpect(jsonPath("$.quote").doesNotExist());

		// Both selection periods end by themselves: J1's Deadline and J5's each find the Job no longer Open. J5's is due
		// 14 days after the decline, an hour before the Agreement deadline the switch set.
		this.clock.advance(Duration.ofDays(14).minusMinutes(30));
		this.poller.runDue();
		assertThat(state(job)).isEqualTo("AGREEMENT");
		assertThat(this.jdbc.sql("""
				SELECT generation || ' ' || status FROM timers.deadline WHERE kind = 'job.selection_lapse' AND subject_id = :id
				ORDER BY generation
				""").param("id", job).query(String.class).list()).containsExactly("1 OBSOLETE", "3 OBSOLETE");
	}

	@Test
	void theSwitchIsRefusedWhileABidIsOpenWithoutTheNoticeAtAnOlderVersionAndForAnyoneElse() throws Exception {
		UUID job = publishedJob();
		UUID hung = mentor("Đỗ Mạnh Hùng");
		UUID bid = application(job, hung);

		switchIt(job, 1, NOTICE).andExpect(status().is(422)).andExpect(jsonPath("$.code").value("GUARD_FAILED"))
			.andExpect(jsonPath("$.reason").value("OPEN_APPLICATIONS"))
			.andExpect(jsonPath("$.count").value(1));
		assertThat(state(job)).isEqualTo("OPEN");
		assertThat(this.jdbc.sql("SELECT self_mentored FROM job.job WHERE id = :id").param("id", job).query(Boolean.class).single())
			.isFalse();
		assertThat(this.jdbc.sql("SELECT status FROM matching.application WHERE id = :id").param("id", bid)
			.query(String.class).single()).as("a refused switch leaves the bid alone").isEqualTo("SUBMITTED");

		// The bidder withdraws; a withdrawn bid is not open.
		this.mvc.perform(post("/api/v1/mentor/applications/" + bid + "/withdraw").with(as(hung)).with(csrf()))
			.andExpect(status().isOk());
		switchIt(job, 1, null).andExpect(status().isBadRequest()).andExpect(jsonPath("$.reason").value("SELF_MENTORED_NOTICE"));
		switchIt(job, 1, "0.9").andExpect(status().isBadRequest()).andExpect(jsonPath("$.reason").value("SELF_MENTORED_NOTICE"));
		switchIt(job, 0, NOTICE).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("STALE"));
		assertThat(state(job)).isEqualTo("OPEN");

		// Someone else finds nothing: another Customer, and the person who bid.
		UUID other = IdentityTestSupport.insertAccount(this.jdbc, Role.CUSTOMER, null);
		for (UUID stranger : List.of(other, hung)) {
			this.mvc.perform(post("/api/v1/jobs/" + job + "/self-mentored").with(as(stranger)).with(csrf())
				.contentType(MediaType.APPLICATION_JSON).content(request(1, NOTICE))).andExpect(status().isNotFound());
		}
		// A Customer who unlinked GitHub is asked to link it first, as for publishing (FR-80).
		IdentityTestSupport.unlinkGitHub(this.jdbc, this.lan);
		switchIt(job, 1, NOTICE).andExpect(status().isForbidden()).andExpect(jsonPath("$.reason").value("GITHUB_NOT_LINKED"));
		assertThat(state(job)).isEqualTo("OPEN");
	}

	@Test
	void aBidThatRacedTheSwitchIsWithdrawnByTheListenerOrTheSweepAndItsBidderTold() throws Exception {
		// The bid committed while the switch ran: the guard counted none, and the listener finds it.
		UUID job = publishedJob();
		UUID hung = mentor("Đỗ Mạnh Hùng");
		UUID raced = application(job, hung);
		this.jdbc.sql("""
				INSERT INTO notification.todo (id, todo_key, account_id, subject_id, tier, params, link, job_id, opened_at)
				VALUES (gen_random_uuid(), 'matching.choose_assignee', :lan, :job, 2, '{"n":"1"}'::jsonb, '/cong-viec', :job, now())
				""").param("lan", this.lan).param("job", job).update();

		assertThat(this.selection.selfMentor(job, this.lan, NOTICE, () -> 0, 1)).isEqualTo(2);

		await().atMost(Duration.ofSeconds(10)).until(() -> "WITHDRAWN".equals(applicationStatus(raced)));
		assertThat(this.jdbc.sql("""
				SELECT end_reason || ' ' || (ended_at IS NOT NULL) || ' ' || (proof_due_at IS NOT NULL)
				FROM matching.application WHERE id = :id
				""").param("id", raced).query(String.class).single()).isEqualTo("JOB_SELF_MENTORED true true");
		await().atMost(Duration.ofSeconds(10)).until(() -> notifications("matching.application_job_self_mentored", hung) == 1);
		assertThat(this.jdbc.sql("""
				SELECT count(*) FROM notification.todo WHERE todo_key = 'matching.choose_assignee' AND job_id = :job AND closed_at IS NULL
				""").param("job", job).query(Long.class).single()).as("there is nobody to choose any more").isZero();
		await().atMost(Duration.ofSeconds(10)).until(() -> count("""
				SELECT count(*) FROM notification.todo WHERE todo_key = 'matching.open_crew_recruitment' AND job_id = :id
				""", job) == 1);

		// A bid that commits after the listener ran is the sweep's. A normal Open Job's bid is not touched, and neither is
		// a bid left on a Draft whose Customer then chose the mode: nobody switched that Job.
		UUID ha = mentor("Phạm Thu Hà");
		UUID late = application(job, ha);
		UUID normal = publishedJob();
		UUID kept = application(normal, ha);
		UUID draft = publishedJob();
		UUID onDraft = application(draft, ha);
		this.jdbc.sql("""
				UPDATE job.job SET state = 'DRAFT', self_mentored = TRUE, rules = NULL, selection_days = NULL, opened_at = NULL,
				    lapses_at = NULL WHERE id = :id
				""").param("id", draft).update();

		assertThat(this.bids.sweep()).isOne();

		assertThat(this.jdbc.sql("SELECT status || ' ' || end_reason FROM matching.application WHERE id = :id")
			.param("id", late).query(String.class).single()).isEqualTo("WITHDRAWN JOB_SELF_MENTORED");
		assertThat(notifications("matching.application_job_self_mentored", ha)).isOne();
		assertThat(applicationStatus(kept)).isEqualTo("SUBMITTED");
		assertThat(applicationStatus(onDraft)).isEqualTo("SUBMITTED");
		assertThat(this.bids.sweep()).as("nothing is left to withdraw").isZero();
		assertThat(notifications("matching.application_job_self_mentored", ha)).isOne();
		// The bidder reads why their bid ended.
		this.mvc.perform(get("/api/v1/mentor/applications").with(as(ha))).andExpect(status().isOk())
			.andExpect(jsonPath("$[?(@.id == '" + late + "')].endReason").value("JOB_SELF_MENTORED"));
	}

	private ResultActions switchIt(UUID job, long version, String notice) throws Exception {
		return this.mvc.perform(post("/api/v1/jobs/" + job + "/self-mentored").with(as(this.lan)).with(csrf())
			.contentType(MediaType.APPLICATION_JSON).content(request(version, notice)));
	}

	private static String request(long version, String notice) {
		return "{\"version\":" + version + (notice == null ? "" : ",\"selfMentoredNoticeVersion\":\"" + notice + "\"") + "}";
	}

	/** A normal Job published through J1, so it holds the copied rules and its selection lapse is scheduled. */
	private UUID publishedJob() throws Exception {
		String created = this.mvc.perform(post("/api/v1/jobs").with(as(this.lan)).with(csrf())
			.contentType(MediaType.APPLICATION_JSON).content(body())).andExpect(status().isCreated())
			.andReturn().getResponse().getContentAsString();
		UUID job = UUID.fromString(com.jayway.jsonpath.JsonPath.read(created, "$.id"));
		this.mvc.perform(post("/api/v1/jobs/" + job + "/publish").with(as(this.lan)).with(csrf()))
			.andExpect(status().isOk()).andExpect(jsonPath("$.state").value("OPEN")).andExpect(jsonPath("$.version").value(1));
		return job;
	}

	private String body() {
		return """
				{"productId":"%s","title":"Thêm thanh toán online","problem":"Khách chưa trả tiền trước được.",
				 "expected":"Khách trả tiền cọc khi đặt bàn.","appUsers":"Khách đặt bàn trên điện thoại.",
				 "jobTypes":["BUG_FIX"],"skillIds":[],"budget":"FROM_2M_TO_5M","deadline":"2026-12-15"}
				""".formatted(this.product);
	}

	private UUID application(UUID job, UUID mentor) {
		UUID id = UUID.randomUUID();
		this.clock.advance(Duration.ofSeconds(1));
		this.jdbc.sql("""
				INSERT INTO matching.application (id, job_id, product_id, customer_account_id, mentor_account_id, status,
				    price_vnd, timeline_days, approach, public_code_notice_version, submitted_at)
				VALUES (:id, :job, :product, :owner, :mentor, 'SUBMITTED', 800000, 7, 'Cách làm', '2.0', :now)
				""")
			.param("id", id)
			.param("job", job)
			.param("product", this.product)
			.param("owner", this.lan)
			.param("mentor", mentor)
			.param("now", Timestamp.from(this.clock.instant()))
			.update();
		return id;
	}

	private String applicationStatus(UUID application) {
		return this.jdbc.sql("SELECT status FROM matching.application WHERE id = :id").param("id", application)
			.query(String.class).single();
	}

	private long notifications(String type, UUID account) {
		return this.jdbc.sql("SELECT count(*) FROM notification.notification WHERE type = :type AND account_id = :account")
			.param("type", type).param("account", account).query(Long.class).single();
	}

	private UUID mentor(String name) {
		return TestPeople.mentor(this.jdbc, name, List.of(TestPeople.REACT), "React");
	}

	private String state(UUID job) {
		return this.jdbc.sql("SELECT state FROM job.job WHERE id = :id").param("id", job).query(String.class).single();
	}

	private long count(String sql, UUID job) {
		return this.jdbc.sql(sql).param("id", job).query(Long.class).single();
	}

	private UUID product(UUID owner) {
		UUID id = UUID.randomUUID();
		Timestamp now = Timestamp.from(MutableClock.DEFAULT_INSTANT);
		this.jdbc.sql("""
				INSERT INTO product.product (id, customer_account_id, name, description, ai_tools, repository_github_id,
				    repository_full_name, repository_owner_type, repository_default_branch, public_notice_version,
				    copy_permission_version, detection_notice_version, acknowledged_at, created_at)
				VALUES (:id, :owner, 'Sổ đặt bàn Quán Mây', 'Đặt bàn cho quán.', ARRAY['LOVABLE'], :github,
				    'lan/so-dat-ban', 'User', 'main', '1.0', '1.0', '1.0', :now, :now)
				""")
			.param("id", id)
			.param("owner", owner)
			.param("github", ThreadLocalRandom.current().nextLong(1, Long.MAX_VALUE))
			.param("now", now)
			.update();
		return id;
	}

	/** Signed in; an account's role in a Job comes from the Job, not from the session (AD-43). */
	private RequestPostProcessor as(UUID account) {
		return IdentityTestSupport.signedIn(this.sessions, account, Role.CUSTOMER, this.clock.instant());
	}

}
