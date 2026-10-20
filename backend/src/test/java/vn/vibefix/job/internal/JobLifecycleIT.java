package vn.vibefix.job.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
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
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import vn.vibefix.identity.internal.IdentityTestSupport;
import vn.vibefix.job.JobTransitioned;
import vn.vibefix.job.internal.JobLifecycle.Actor;
import vn.vibefix.shared.ApiException;
import vn.vibefix.shared.ErrorCode;
import vn.vibefix.shared.Role;
import vn.vibefix.support.IntegrationTest;
import vn.vibefix.support.MutableClock;
import vn.vibefix.timers.internal.DeadlinePoller;

/** PRD section 4.0, AD-4, AD-7, FR-9: J1 publishes a Draft as a Job Post, and J2 returns it. */
@RecordApplicationEvents
class JobLifecycleIT extends IntegrationTest {

	@Autowired
	private MockMvc mvc;

	@Autowired
	private JdbcClient jdbc;

	@Autowired
	private SessionRepository<? extends Session> sessions;

	@Autowired
	private JobLifecycle lifecycle;

	@Autowired
	private DeadlinePoller poller;

	@Autowired
	private PlatformTransactionManager transactions;

	@Autowired
	private ApplicationEvents events;

	private UUID lan;

	private UUID product;

	@BeforeEach
	void aCustomerWithAProduct() {
		this.lan = IdentityTestSupport.insertAccount(this.jdbc, Role.CUSTOMER, null, null);
		this.product = product(this.lan);
	}

	@Test
	void publishingMakesAnOpenJobPostWithTheRulesOfThatDay() throws Exception {
		UUID job = draft("[\"BUG_FIX\"]", "\"FROM_2M_TO_5M\"", "\"2026-11-15\"");
		Instant lapsesAt = this.clock.instant().plus(Duration.ofDays(14));

		publish(job).andExpect(status().isOk())
			.andExpect(jsonPath("$.state").value("OPEN"))
			.andExpect(jsonPath("$.version").value(1))
			.andExpect(jsonPath("$.openedAt").value(this.clock.instant().toString()))
			.andExpect(jsonPath("$.lapsesAt").value(lapsesAt.toString()));

		assertThat(this.jdbc.sql("SELECT rules ->> 'AUTO_ACCEPTANCE_DAYS' FROM job.job WHERE id = :id")
			.param("id", job).query(String.class).single()).isEqualTo("7");
		assertThat(this.jdbc.sql("SELECT jsonb_object_keys(rules) FROM job.job WHERE id = :id")
			.param("id", job).query(String.class).list()).hasSize(vn.vibefix.settings.Rule.values().length);
		assertThat(this.jdbc.sql("SELECT selection_days FROM job.job WHERE id = :id").param("id", job)
			.query(Integer.class).single()).isEqualTo(14);
		assertThat(this.jdbc.sql("""
				SELECT count(*) FROM timers.deadline
				WHERE kind = 'job.selection_lapse' AND subject_id = :id AND job_id = :id AND generation = '1'
				  AND NOT pausable AND status = 'PENDING' AND due_at = :due
				""").param("id", job).param("due", Timestamp.from(lapsesAt)).query(Long.class).single()).isOne();
		assertThat(this.jdbc.sql("""
				SELECT count(*) FROM audit.audit_entry
				WHERE action = 'job.transition_j1' AND job_id = :id AND actor_account_id = :lan
				""").param("id", job).param("lan", this.lan).query(Long.class).single()).isOne();
		List<JobTransitioned> published = this.events.stream(JobTransitioned.class).toList();
		assertThat(published).singleElement().satisfies(event -> {
			assertThat(event.jobId()).isEqualTo(job);
			assertThat(event.transition()).isEqualTo("J1");
			assertThat(event.trigger()).isEqualTo("USER");
			assertThat(event.from()).isEqualTo("DRAFT");
			assertThat(event.to()).isEqualTo("OPEN");
			assertThat(event.version()).isEqualTo(1);
			assertThat(event.facts()).containsEntry("lapsesAt", lapsesAt.toString());
		});

		this.jdbc.sql("UPDATE settings.rule_value SET value = 10 WHERE rule_key = 'AUTO_ACCEPTANCE_DAYS'").update();
		try {
			assertThat(this.jdbc.sql("SELECT rules ->> 'AUTO_ACCEPTANCE_DAYS' FROM job.job WHERE id = :id")
				.param("id", job).query(String.class).single()).as("a later setting never alters the Job").isEqualTo("7");
		}
		finally {
			this.jdbc.sql("UPDATE settings.rule_value SET value = 7 WHERE rule_key = 'AUTO_ACCEPTANCE_DAYS'").update();
		}
		this.mvc.perform(put("/api/v1/jobs/" + job).with(as(this.lan)).with(csrf())
			.contentType(MediaType.APPLICATION_JSON)
			.content(body("[\"BUG_FIX\"]", "null", "null")))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.reason").value("JOB_NOT_DRAFT"));
		publish(job).andExpect(status().isConflict()).andExpect(jsonPath("$.reason").value("STATE_CHANGED"));
	}

	@Test
	void aCodeJobWaitsForItsIntakeFindingsAndAnIncompleteDraftIsNotPublished() throws Exception {
		UUID code = draft("[\"BUG_FIX\"]", "\"UNDER_2M\"", "\"2026-11-15\"");
		UUID assessment = draft("[\"ASSESSMENT\"]", "\"UNDER_2M\"", "\"2026-11-15\"");
		UUID incomplete = draft("[]", "null", "\"2026-01-05\"");
		openFinding(this.product);

		publish(code).andExpect(status().is(422))
			.andExpect(jsonPath("$.code").value("GUARD_FAILED"))
			.andExpect(jsonPath("$.reason").value("INTAKE_FINDING_OPEN"))
			.andExpect(jsonPath("$.count").value(1));
		publish(assessment).andExpect(status().isOk()).andExpect(jsonPath("$.state").value("OPEN"));
		publish(incomplete).andExpect(status().is(422))
			.andExpect(jsonPath("$.reason").value("JOB_INCOMPLETE"))
			.andExpect(jsonPath("$.missing[0]").value("jobTypes"))
			.andExpect(jsonPath("$.missing[1]").value("budget"))
			.andExpect(jsonPath("$.missing[2]").value("deadline"));
		assertThat(state(code)).isEqualTo("DRAFT");
		assertThat(this.jdbc.sql("SELECT count(*) FROM audit.audit_entry WHERE action = 'job.transition_j1'")
			.query(Long.class).single()).as("a refusal writes nothing").isOne();

		UUID other = IdentityTestSupport.insertAccount(this.jdbc, Role.CUSTOMER, null, null);
		this.mvc.perform(post("/api/v1/jobs/" + assessment + "/withdraw").with(as(other)).with(csrf()))
			.andExpect(status().isNotFound());
	}

	@Test
	void aProductCanHaveSeveralOpenJobPosts() throws Exception {
		UUID first = draft("[\"BUG_FIX\"]", "\"UNDER_2M\"", "\"2026-11-15\"");
		UUID second = draft("[\"DOCUMENTATION\"]", "\"OVER_10M\"", "\"2026-12-01\"");

		publish(first).andExpect(status().isOk());
		publish(second).andExpect(status().isOk());

		assertThat(List.of(state(first), state(second))).containsOnly("OPEN");
	}

	@Test
	void withdrawingReturnsItToDraftAndOnlyTheNewestLapseFires() throws Exception {
		UUID job = draft("[\"NEW_FEATURE\"]", "\"FROM_5M_TO_10M\"", "\"2026-11-15\"");
		publish(job).andExpect(status().isOk());

		this.mvc.perform(post("/api/v1/jobs/" + job + "/withdraw").with(as(this.lan)).with(csrf()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.state").value("DRAFT"))
			.andExpect(jsonPath("$.version").value(2))
			.andExpect(jsonPath("$.openedAt").value(org.hamcrest.Matchers.nullValue()))
			.andExpect(jsonPath("$.lapsesAt").value(org.hamcrest.Matchers.nullValue()));
		assertThat(this.jdbc.sql("SELECT rules IS NULL AND selection_days IS NULL FROM job.job WHERE id = :id")
			.param("id", job).query(Boolean.class).single()).isTrue();
		this.mvc.perform(post("/api/v1/jobs/" + job + "/withdraw").with(as(this.lan)).with(csrf()))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("STALE"))
			.andExpect(jsonPath("$.reason").value("STATE_CHANGED"));
		assertThat(notifications()).as("a withdrawal tells nobody").isZero();

		this.clock.advance(Duration.ofDays(1));
		publish(job).andExpect(status().isOk()).andExpect(jsonPath("$.version").value(3));
		this.clock.advance(Duration.ofDays(13).plusMinutes(1));
		this.poller.runDue();

		assertThat(state(job)).as("the first lapse is obsolete; the second is due a day later").isEqualTo("OPEN");
		assertThat(this.jdbc.sql("SELECT status FROM timers.deadline WHERE subject_id = :id AND generation = '1'")
			.param("id", job).query(String.class).single()).isEqualTo("OBSOLETE");

		this.clock.advance(Duration.ofDays(1));
		this.poller.runDue();

		assertThat(state(job)).isEqualTo("DRAFT");
		assertThat(this.jdbc.sql("SELECT status FROM timers.deadline WHERE subject_id = :id AND generation = '3'")
			.param("id", job).query(String.class).single()).isEqualTo("FIRED");
		assertThat(this.jdbc.sql("""
				SELECT count(*) FROM audit.audit_entry
				WHERE action = 'job.transition_j2' AND job_id = :id AND actor_account_id IS NULL
				""").param("id", job).query(Long.class).single()).as("the lapse is the System's").isOne();
		assertThat(this.jdbc.sql("""
				SELECT params ->> 'job' FROM notification.notification
				WHERE type = 'job.job_post_lapsed' AND account_id = :lan AND job_id = :id AND link = :link
				""").param("lan", this.lan).param("id", job).param("link", "/cong-viec/" + job + "/sua")
			.query(String.class).single()).isEqualTo("Thêm thanh toán online");
		assertThat(this.events.stream(JobTransitioned.class).filter(event -> event.transition().equals("J2")))
			.extracting(event -> event.facts().get("reason"), JobTransitioned::trigger)
			.containsExactly(org.assertj.core.groups.Tuple.tuple("WITHDRAWN", "USER"),
					org.assertj.core.groups.Tuple.tuple("LAPSED", "AUTO"));
	}

	@Test
	void theLifecycleRefusesALockedActorAndTransitionsWithoutACommand() throws Exception {
		UUID job = draft("[\"BUG_FIX\"]", "\"UNDER_2M\"", "\"2026-11-15\"");
		TransactionTemplate transaction = new TransactionTemplate(this.transactions);

		assertThatThrownBy(() -> transaction.executeWithoutResult(
				status -> this.lifecycle.apply(Transition.J8, job, Actor.SYSTEM, Trigger.SYSTEM)))
			.isInstanceOf(IllegalStateException.class);
		assertThatThrownBy(() -> transaction.executeWithoutResult(
				status -> this.lifecycle.apply(Transition.J1, job, Actor.SYSTEM, Trigger.AUTO)))
			.isInstanceOf(IllegalArgumentException.class);
		this.jdbc.sql("UPDATE identity.account SET locked_at = :now, lock_reason = 'Vi phạm quy định' WHERE id = :id")
			.param("now", Timestamp.from(this.clock.instant()))
			.param("id", this.lan)
			.update();
		assertThatThrownBy(() -> transaction.executeWithoutResult(
				status -> this.lifecycle.apply(Transition.J1, job, Actor.user(this.lan), Trigger.USER)))
			.isInstanceOfSatisfying(ApiException.class, refused -> assertThat(refused.code()).isEqualTo(ErrorCode.FORBIDDEN));
		assertThat(state(job)).isEqualTo("DRAFT");
	}

	private ResultActions publish(UUID job) throws Exception {
		return this.mvc.perform(post("/api/v1/jobs/" + job + "/publish").with(as(this.lan)).with(csrf()));
	}

	private UUID draft(String types, String budget, String deadline) throws Exception {
		String created = this.mvc.perform(post("/api/v1/jobs").with(as(this.lan)).with(csrf())
			.contentType(MediaType.APPLICATION_JSON)
			.content(body(types, budget, deadline))).andExpect(status().isCreated()).andReturn().getResponse()
			.getContentAsString();
		return UUID.fromString(com.jayway.jsonpath.JsonPath.read(created, "$.id"));
	}

	private String body(String types, String budget, String deadline) {
		return """
				{"productId":"%s","title":"Thêm thanh toán online","problem":"Khách chưa trả tiền trước được.",
				 "expected":"Khách trả tiền cọc khi đặt bàn.","appUsers":"Khách đặt bàn trên điện thoại.",
				 "jobTypes":%s,"skillIds":[],"budget":%s,"deadline":%s}
				""".formatted(this.product, types, budget, deadline);
	}

	private String state(UUID job) {
		return this.jdbc.sql("SELECT state FROM job.job WHERE id = :id").param("id", job).query(String.class).single();
	}

	private long notifications() {
		return this.jdbc.sql("SELECT count(*) FROM notification.notification").query(Long.class).single();
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

	private void openFinding(UUID productId) {
		UUID run = UUID.randomUUID();
		Timestamp now = Timestamp.from(MutableClock.DEFAULT_INSTANT);
		this.jdbc.sql("""
				INSERT INTO scanning.scan_run (id, product_id, customer_account_id, product_name, repository_full_name,
				    repository_github_id, purpose, status, rules_version, found_count, new_findings, requested_at, finished_at)
				VALUES (:id, :product, :owner, 'Sổ', 'lan/so-dat-ban', 1, 'INTAKE', 'SUCCEEDED', 2, 1, 1, :now, :now)
				""").param("id", run).param("product", productId).param("owner", this.lan).param("now", now).update();
		this.jdbc.sql("""
				INSERT INTO scanning.finding (id, product_id, scan_run_id, category, rule_id, rule_type, file_path,
				    commit_sha, committed_at, masked_preview, fingerprint, status, created_at)
				VALUES (:id, :product, :run, 'CREDENTIAL', 'stripe-secret-key', 'Khóa bí mật Stripe', 'pay.js',
				    repeat('a', 40), :now, 'sk_l••••••••Q7mZ', repeat('c', 64), 'OPEN', :now)
				""").param("id", UUID.randomUUID()).param("product", productId).param("run", run).param("now", now).update();
	}

	private RequestPostProcessor as(UUID account) {
		return IdentityTestSupport.signedIn(this.sessions, account, Role.CUSTOMER, this.clock.instant());
	}

}
