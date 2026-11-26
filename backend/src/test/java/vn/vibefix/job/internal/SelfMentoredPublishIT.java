package vn.vibefix.job.internal;

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
import vn.vibefix.support.IntegrationTest;
import vn.vibefix.support.MutableClock;

/**
 * FR-85, AD-47, AD-4, Story 25.2a: with {@code features.selfMentoredJobs} on, a Customer chooses "Tôi tự hướng dẫn" on
 * a Draft and publishes it through J11, straight into Agreement, as the Job's Mentor. A normal Job is untouched.
 */
@TestPropertySource(properties = "vibefix.features.self-mentored-jobs=true")
class SelfMentoredPublishIT extends IntegrationTest {

	private static final String NOTICE = "{\"selfMentoredNoticeVersion\":\"" + J11PublishSelfMentored.NOTICE_VERSION + "\"}";

	@Autowired
	private MockMvc mvc;

	@Autowired
	private JdbcClient jdbc;

	@Autowired
	private SessionRepository<? extends Session> sessions;

	@Autowired
	private JobRoles roles;

	private UUID lan;

	private UUID product;

	@BeforeEach
	void aCustomerWithAProduct() {
		this.lan = IdentityTestSupport.insertAccount(this.jdbc, Role.CUSTOMER, null);
		this.product = product(this.lan);
	}

	@Test
	void theModeIsAChoiceOnADraftInEitherDirection() throws Exception {
		UUID job = draft(true, "\"2026-11-15\"");

		this.mvc.perform(get("/api/v1/jobs/" + job).with(as(this.lan))).andExpect(status().isOk())
			.andExpect(jsonPath("$.selfMentored").value(true))
			.andExpect(jsonPath("$.selfMentoredOffered").value(true))
			.andExpect(jsonPath("$.state").value("DRAFT"));
		assertThat(this.roles.roleOf(job, this.lan)).as("a Draft's Customer is the Customer, whatever its mode")
			.isEqualTo(JobRole.CUSTOMER);

		save(job, false).andExpect(status().isOk()).andExpect(jsonPath("$.selfMentored").value(false));
		save(job, true).andExpect(status().isOk()).andExpect(jsonPath("$.selfMentored").value(true));
		// A request that says nothing about the mode is a normal Job's, as before the mode existed.
		this.mvc.perform(put("/api/v1/jobs/" + job).with(as(this.lan)).with(csrf()).contentType(MediaType.APPLICATION_JSON)
			.content(body(null, "\"2026-11-15\""))).andExpect(status().isOk())
			.andExpect(jsonPath("$.selfMentored").value(false));
	}

	@Test
	void publishingASelfMentoredDraftEntersAgreementWithTheCustomerAsTheMentor() throws Exception {
		UUID job = draft(true, "\"2026-11-15\"");
		Instant now = this.clock.instant();
		Instant due = now.plus(Duration.ofDays(14));

		publish(job, NOTICE).andExpect(status().isOk())
			.andExpect(jsonPath("$.state").value("AGREEMENT"))
			.andExpect(jsonPath("$.selfMentored").value(true))
			.andExpect(jsonPath("$.version").value(1))
			.andExpect(jsonPath("$.mentor").doesNotExist())
			.andExpect(jsonPath("$.ndaDueAt").doesNotExist())
			.andExpect(jsonPath("$.openedAt").doesNotExist());

		assertThat(this.jdbc.sql("""
				SELECT (mentor_account_id = customer_account_id) || ' ' || (selected_application_id IS NULL) || ' '
				    || (selected_at = :now) || ' ' || (agreement_due_at = :due) || ' ' || (opened_at IS NULL) || ' '
				    || selection_days || ' ' || (rules ->> 'AUTO_ACCEPTANCE_DAYS')
				FROM job.job WHERE id = :id
				""").param("id", job).param("now", Timestamp.from(now)).param("due", Timestamp.from(due))
			.query(String.class).single()).isEqualTo("true true true true true 14 7");
		assertThat(this.jdbc.sql("SELECT jsonb_object_keys(rules) FROM job.job WHERE id = :id").param("id", job)
			.query(String.class).list()).as("every FR-61 rule is copied, as at J1").hasSize(vn.vibefix.settings.Rule.values().length);
		assertThat(this.roles.roleOf(job, this.lan)).isEqualTo(JobRole.CUSTOMER_MENTOR);
		// The Job keeps the notice the Customer read, and the audit names the transition and its one actor.
		assertThat(this.jdbc.sql("SELECT self_mentored_notice_version FROM job.job WHERE id = :id").param("id", job)
			.query(String.class).single()).isEqualTo(J11PublishSelfMentored.NOTICE_VERSION);
		assertThat(this.jdbc.sql("""
				SELECT count(*) FROM audit.audit_entry
				WHERE action = 'job.transition_j11' AND job_id = :id AND actor_account_id = :lan
				""").param("id", job).param("lan", this.lan).query(Long.class).single()).isOne();
		// No selection: nothing lapses, nobody is told a Mentor was selected, and no Application exists.
		assertThat(count("SELECT count(*) FROM timers.deadline WHERE kind = 'job.selection_lapse' AND subject_id = :id", job)).isZero();
		assertThat(count("SELECT count(*) FROM matching.application WHERE job_id = :id", job)).isZero();
		// Drafting starts: the draft exists and its to-do is the Customer's.
		assertThat(count("SELECT count(*) FROM job.agreement_draft WHERE job_id = :id", job)).isOne();
		assertThat(this.jdbc.sql("""
				SELECT account_id FROM notification.todo WHERE todo_key = 'job.draft_agreement' AND job_id = :id AND closed_at IS NULL
				""").param("id", job).query(UUID.class).single()).isEqualTo(this.lan);
		// The listeners: the Crew Chat alone, the Agreement stall, and the recruitment to-do.
		await().atMost(Duration.ofSeconds(10)).until(() -> count("SELECT count(*) FROM chat.chat WHERE job_id = :id", job) == 1
				&& count("SELECT count(*) FROM timers.deadline WHERE job_id = :id AND kind = 'job.agreement_deadline'", job) == 1
				&& count("""
						SELECT count(*) FROM notification.todo WHERE todo_key = 'matching.open_crew_recruitment' AND job_id = :id
						""", job) == 1);
		assertThat(this.jdbc.sql("SELECT kind FROM chat.chat WHERE job_id = :id").param("id", job).query(String.class).list())
			.as("a Self-mentored Job never has a Job Chat").containsExactly("CREW");
		assertThat(this.jdbc.sql("""
				SELECT account_id FROM notification.todo WHERE todo_key = 'matching.open_crew_recruitment' AND job_id = :id
				""").param("id", job).query(UUID.class).single()).isEqualTo(this.lan);
		assertThat(count("SELECT count(*) FROM notification.notification WHERE type = 'chat.chat_opened' AND job_id = :id", job))
			.isZero();

		// From J11 the mode is one-way: the Draft is gone, and nothing publishes it again.
		save(job, false).andExpect(status().isConflict()).andExpect(jsonPath("$.reason").value("JOB_NOT_DRAFT"));
		publish(job, NOTICE).andExpect(status().isConflict()).andExpect(jsonPath("$.reason").value("STATE_CHANGED"));
		assertThat(this.jdbc.sql("SELECT self_mentored FROM job.job WHERE id = :id").param("id", job).query(Boolean.class).single())
			.isTrue();
	}

	@Test
	void j11IsRefusedWithoutTheNoticeOrWithAnIncompleteDraftAndJ1NeverTakesASelfMentoredDraft() throws Exception {
		UUID job = draft(true, "\"2026-11-15\"");

		publish(job, null).andExpect(status().isBadRequest()).andExpect(jsonPath("$.reason").value("SELF_MENTORED_NOTICE"));
		publish(job, "{\"selfMentoredNoticeVersion\":\"0.9\"}").andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.reason").value("SELF_MENTORED_NOTICE"));
		assertThat(state(job)).isEqualTo("DRAFT");
		assertThat(this.roles.roleOf(job, this.lan)).isEqualTo(JobRole.CUSTOMER);

		UUID incomplete = draft(true, "null");
		publish(incomplete, NOTICE).andExpect(status().is(422)).andExpect(jsonPath("$.reason").value("JOB_INCOMPLETE"))
			.andExpect(jsonPath("$.missing[0]").value("deadline"));

		// Someone else finds nothing, whatever they send.
		UUID other = IdentityTestSupport.insertAccount(this.jdbc, Role.CUSTOMER, null);
		this.mvc.perform(post("/api/v1/jobs/" + job + "/publish").with(as(other)).with(csrf())
			.contentType(MediaType.APPLICATION_JSON).content(NOTICE)).andExpect(status().isNotFound());

		// A normal Draft still takes J1 and becomes an Open Job Post, with or without a body.
		UUID normal = draft(false, "\"2026-11-15\"");
		publish(normal, NOTICE).andExpect(status().isOk()).andExpect(jsonPath("$.state").value("OPEN"))
			.andExpect(jsonPath("$.selfMentored").value(false));
		assertThat(this.roles.roleOf(normal, this.lan)).isEqualTo(JobRole.CUSTOMER);
	}

	private ResultActions publish(UUID job, String body) throws Exception {
		var request = post("/api/v1/jobs/" + job + "/publish").with(as(this.lan)).with(csrf());
		return this.mvc.perform(body == null ? request : request.contentType(MediaType.APPLICATION_JSON).content(body));
	}

	private ResultActions save(UUID job, boolean selfMentored) throws Exception {
		return this.mvc.perform(put("/api/v1/jobs/" + job).with(as(this.lan)).with(csrf())
			.contentType(MediaType.APPLICATION_JSON).content(body(selfMentored, "\"2026-11-15\"")));
	}

	private UUID draft(boolean selfMentored, String deadline) throws Exception {
		String created = this.mvc.perform(post("/api/v1/jobs").with(as(this.lan)).with(csrf())
			.contentType(MediaType.APPLICATION_JSON).content(body(selfMentored, deadline))).andExpect(status().isCreated())
			.andReturn().getResponse().getContentAsString();
		return UUID.fromString(com.jayway.jsonpath.JsonPath.read(created, "$.id"));
	}

	/** A Draft's answers; {@code selfMentored} absent is a client that knows no mode. */
	private String body(Boolean selfMentored, String deadline) {
		return """
				{"productId":"%s","title":"Thêm thanh toán online","problem":"Khách chưa trả tiền trước được.",
				 "expected":"Khách trả tiền cọc khi đặt bàn.","appUsers":"Khách đặt bàn trên điện thoại.",
				 "jobTypes":["BUG_FIX"],"skillIds":[],"budget":"FROM_2M_TO_5M","deadline":%s%s}
				""".formatted(this.product, deadline, selfMentored == null ? "" : ",\"selfMentored\":" + selfMentored);
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

	private RequestPostProcessor as(UUID account) {
		return IdentityTestSupport.signedIn(this.sessions, account, Role.CUSTOMER, this.clock.instant());
	}

}
