package vn.vibefix.delivery.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.session.Session;
import org.springframework.session.SessionRepository;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.support.TransactionTemplate;

import vn.vibefix.identity.internal.IdentityTestSupport;
import vn.vibefix.job.MilestoneFunding;
import vn.vibefix.shared.Role;
import vn.vibefix.timers.internal.DeadlinePoller;
import vn.vibefix.support.IntegrationTest;
import vn.vibefix.team.internal.TeamTestSupport;

/**
 * FR-33, FR-34, FR-42, AD-5, AD-15, AD-41: only the Customer decides a handover, on the loaded version;
 * acceptance applies M5 and writes one RELEASE per party of the pinned Crew Terms, the remainder to the
 * Mentor, in one transaction, tells everyone, and opens the next Milestone's funding; a revision names
 * the criteria "Chưa đạt", uses one Revision Round, and reopens the handover to-do; a decided handover
 * stays decided; with no round left the server refuses a revision.
 */
class DecisionIT extends IntegrationTest {

	private static final long PRICE = 1_000_001;

	@Autowired
	private MockMvc mvc;

	@Autowired
	private JdbcClient jdbc;

	@Autowired
	private SessionRepository<? extends Session> sessions;

	@Autowired
	private DeadlinePoller poller;

	@Autowired
	private MilestoneFunding funding;

	@Autowired
	private TransactionTemplate transactions;

	private UUID lan;

	private UUID hung;

	private UUID vy;

	private UUID job;

	private UUID milestone;

	private UUID next;

	private UUID criterion;

	@Test
	void acceptanceReleasesTheMilestoneByThePinnedCrewTermsInOneGo() throws Exception {
		world(2);
		String handover = handOver(5);
		decide(this.hung, Role.MENTOR, handover, "accept", 6, true, null, null).andExpect(status().isForbidden())
			.andExpect(jsonPath("$.reason").value("CUSTOMER_ONLY"));
		decide(this.vy, Role.STUDENT, handover, "accept", 6, true, null, null).andExpect(status().isForbidden());
		decide(this.lan, Role.CUSTOMER, handover, "accept", 5, true, null, null).andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("STALE"));
		this.mvc.perform(get("/api/v1/handovers/" + handover).with(as(this.lan, Role.CUSTOMER)))
			.andExpect(jsonPath("$.decision.releaseVnd").value(PRICE))
			.andExpect(jsonPath("$.decision.revisionAllowed").value(true))
			.andExpect(jsonPath("$.roundsLeft").value(2))
			.andExpect(jsonPath("$.version").value(6));

		decide(this.lan, Role.CUSTOMER, handover, "accept", 6, true, null, null).andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value("ACCEPTED"))
			.andExpect(jsonPath("$.decision").doesNotExist())
			.andExpect(jsonPath("$.checks[0].met").value(true))
			.andExpect(jsonPath("$.decidedAt").exists());
		assertThat(this.jdbc.sql("""
				SELECT party_account_id || ':' || amount_vnd || ':' || share_percent || ':' || crew_terms_version_no || ':'
				    || base_amount_vnd FROM payment.ledger_entry WHERE entry_type = 'RELEASE' ORDER BY amount_vnd DESC
				""").query(String.class).list())
			.containsExactly(this.hung + ":700001:70:1:" + PRICE, this.vy + ":300000:30:1:" + PRICE);
		assertThat(text("SELECT state FROM job.milestone WHERE id = '" + this.milestone + "'")).isEqualTo("ACCEPTED");
		assertThat(count("SELECT version FROM job.job WHERE id = '" + this.job + "'")).isEqualTo(7);
		assertThat(notifications("job.milestone_accepted")).isEqualTo(2);
		assertThat(notifications("payment.funds_released")).isEqualTo(2);
		assertThat(openTodos("delivery.review_handover", this.lan)).isZero();
		await().atMost(Duration.ofSeconds(10)).until(() -> this.jdbc.sql("""
				SELECT count(*) FROM notification.todo WHERE todo_key = 'payment.fund_milestone' AND subject_id = :next
				    AND closed_at IS NULL
				""").param("next", this.next).query(Long.class).single() == 1);

		decide(this.lan, Role.CUSTOMER, handover, "accept", 7, true, null, null).andExpect(status().isConflict())
			.andExpect(jsonPath("$.reason").value("HANDOVER_DECIDED"));
		this.mvc.perform(get("/api/v1/boards/" + this.job + "/progress").with(as(this.lan, Role.CUSTOMER)))
			.andExpect(jsonPath("$.milestones[0].state").value("ACCEPTED"));
	}

	@Test
	void theNextMilestoneWaitsForItsPaymentAndBothSidesHearIfItStillDoesFourteenDaysLater() throws Exception {
		world(2);
		String handover = handOver(5);
		assertThat(fundNext()).as("Milestone 1 is still Held (§4.0 M1)").isFalse();
		decide(this.lan, Role.CUSTOMER, handover, "accept", 6, true, null, null).andExpect(status().isOk());

		assertThat(this.jdbc.sql("""
				SELECT (params ->> 'n') || ':' || (params ->> 'amount') FROM notification.notification
				WHERE type = 'job.milestone_awaiting_funding' AND account_id = :lan
				""").param("lan", this.lan).query(String.class).single()).isEqualTo("2:500.000 ₫");
		assertThat(this.jdbc.sql("SELECT kind || ':' || generation || ':' || pausable FROM timers.deadline WHERE subject_id = :next")
			.param("next", this.next).query(String.class).single())
			.isEqualTo("job.milestone_unfunded:" + this.milestone + ":true");

		this.clock.advance(Duration.ofDays(13));
		this.poller.runDue();
		assertThat(notifications("job.milestone_unfunded_reminder")).isZero();
		this.clock.advance(Duration.ofDays(1).plusMinutes(1));
		this.poller.runDue();
		assertThat(this.jdbc.sql("""
				SELECT account_id FROM notification.notification WHERE type = 'job.milestone_unfunded_reminder'
				    AND params ->> 'n' = '2' AND params ->> 'days' = '14'
				""").query(UUID.class).list()).containsExactlyInAnyOrder(this.lan, this.hung);

		// M1 of the next Milestone applies once none is Held, while the Job is Active.
		assertThat(fundNext()).isTrue();
		assertThat(text("SELECT state FROM job.milestone WHERE id = '" + this.next + "'")).isEqualTo("FUNDED");
	}

	@Test
	void aPaidNextMilestoneIsNotReminded() throws Exception {
		world(2);
		decide(this.lan, Role.CUSTOMER, handOver(5), "accept", 6, true, null, null).andExpect(status().isOk());
		assertThat(fundNext()).isTrue();
		this.clock.advance(Duration.ofDays(15));
		this.poller.runDue();
		assertThat(notifications("job.milestone_unfunded_reminder")).isZero();
		assertThat(text("SELECT status FROM timers.deadline WHERE kind = 'job.milestone_unfunded'")).isEqualTo("OBSOLETE");
	}

	@Test
	void aFrozenJobPausesTheReminder() throws Exception {
		world(2);
		decide(this.lan, Role.CUSTOMER, handOver(5), "accept", 6, true, null, null).andExpect(status().isOk());
		this.jdbc.sql("UPDATE job.job SET frozen_at = :now WHERE id = :id")
			.param("now", Timestamp.from(this.clock.instant())).param("id", this.job).update();
		this.clock.advance(Duration.ofDays(15));
		this.poller.runDue();
		assertThat(notifications("job.milestone_unfunded_reminder")).isZero();
		assertThat(text("SELECT status FROM timers.deadline WHERE kind = 'job.milestone_unfunded'")).isEqualTo("PAUSED");
	}

	/** M1 of Milestone 2 as the IPN applies it, with a stand-in Payment Order. */
	private boolean fundNext() {
		return Boolean.TRUE.equals(this.transactions
			.execute(status -> this.funding.tryFund(this.job, this.next, UUID.randomUUID()).isPresent()));
	}

	@Test
	void aRevisionUsesARoundReopensTheHandoverToDoAndStopsWhenNoneIsLeft() throws Exception {
		world(1);
		String first = handOver(5);
		decide(this.lan, Role.CUSTOMER, first, "revision", 6, true, null, null).andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.reason").value("REVISION_CRITERIA_MISSING"));
		decide(this.lan, Role.CUSTOMER, first, "revision", 6, false, "Thiếu mục sao lưu.", null).andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value("REVISION_REQUESTED"))
			.andExpect(jsonPath("$.checks[0].comment").value("Thiếu mục sao lưu."));
		assertThat(text("SELECT state || ':' || revision_rounds_used || ':' || revision_criteria FROM job.milestone WHERE id = '"
				+ this.milestone + "'")).isEqualTo("REVISION_REQUESTED:1:1");
		assertThat(notifications("job.revision_requested")).isOne();
		assertThat(openTodos("delivery.review_handover", this.lan)).isZero();
		await().atMost(Duration.ofSeconds(10)).until(() -> openTodos("delivery.hand_over", this.hung) == 1);
		assertThat(count("SELECT count(*) FROM payment.ledger_entry WHERE entry_type = 'RELEASE'")).isZero();
		this.mvc.perform(get("/api/v1/boards/" + this.job + "/progress").with(as(this.hung, Role.MENTOR)))
			.andExpect(jsonPath("$.milestones[0].revisionCriteria").value(1))
			.andExpect(jsonPath("$.milestones[0].roundsLeft").value(0));

		String second = handOver(7);
		this.mvc.perform(get("/api/v1/handovers/" + second).with(as(this.lan, Role.CUSTOMER)))
			.andExpect(jsonPath("$.round").value(2))
			.andExpect(jsonPath("$.decision.revisionAllowed").value(false));
		decide(this.lan, Role.CUSTOMER, second, "revision", 8, false, "Vẫn thiếu.", null).andExpect(status().isUnprocessableEntity())
			.andExpect(jsonPath("$.reason").value("REVISION_ROUNDS_USED"));
		decide(this.lan, Role.CUSTOMER, first, "accept", 8, true, null, null).andExpect(status().isConflict())
			.andExpect(jsonPath("$.reason").value("HANDOVER_DECIDED"));
	}

	@Test
	void aRevisionCommentPassesTheSensitiveCheck() throws Exception {
		world(2);
		String handover = handOver(5);
		decide(this.lan, Role.CUSTOMER, handover, "revision", 6, false, "Khóa " + fakeToken(), null)
			.andExpect(status().isUnprocessableEntity())
			.andExpect(jsonPath("$.reason").value("SENSITIVE_CONTENT"));
		decide(this.lan, Role.CUSTOMER, handover, "revision", 6, false, "Khóa " + fakeToken(), "MASK").andExpect(status().isOk());
		assertThat(text("SELECT comment FROM delivery.handover_check")).startsWith("Khóa ").doesNotContain(fakeToken());
	}

	/**
	 * An Active Assessment Job with Milestone 1 Funded at {@code PRICE} under Crew Terms version 1 (the
	 * Mentor 70, Vy 30), its seeded Story approved and the Assessment Report written, and Milestone 2
	 * Planned.
	 */
	private void world(int revisionRounds) throws Exception {
		this.lan = IdentityTestSupport.insertAccount(this.jdbc, Role.CUSTOMER, null);
		this.hung = TeamTestSupport.mentor(this.jdbc, "Đỗ Mạnh Hùng",
				List.of(TeamTestSupport.REACT), "React");
		this.vy = TeamTestSupport.student(this.jdbc, "Trần Thảo Vy", true, List.of(TeamTestSupport.REACT), "React",
				LocalDate.parse("2026-09-30"));
		UUID product = UUID.randomUUID();
		this.jdbc.sql("""
				INSERT INTO product.product (id, customer_account_id, name, description, ai_tools, repository_github_id,
				    repository_full_name, repository_owner_type, repository_default_branch, public_notice_version,
				    copy_permission_version, detection_notice_version, acknowledged_at, created_at)
				VALUES (:id, :owner, 'Báo cáo đặt bàn', 'Đặt bàn.', ARRAY['LOVABLE'], :github, 'lan/so-dat-ban', 'User', 'main',
				    '1.0', '1.0', '1.0', now(), now())
				""").param("id", product).param("owner", this.lan)
			.param("github", ThreadLocalRandom.current().nextLong(1, Long.MAX_VALUE)).update();
		this.job = UUID.randomUUID();
		this.milestone = UUID.randomUUID();
		this.next = UUID.randomUUID();
		this.criterion = UUID.randomUUID();
		Timestamp now = Timestamp.from(this.clock.instant());
		this.jdbc.sql("""
				INSERT INTO job.job (id, product_id, customer_account_id, state, title, problem, expected, app_users,
				    job_types, budget, deadline, rules, selection_days, opened_at, lapses_at, mentor_account_id,
				    selected_application_id, selected_at, version, created_at, updated_at)
				VALUES (:id, :product, :owner, 'ACTIVE', 'Đánh giá sổ đặt bàn', 'Lỗi', 'Đúng', 'Khách', ARRAY['ASSESSMENT'],
				    'UNDER_2M', DATE '2026-12-01', '{"AUTO_ACCEPTANCE_DAYS":7}'::jsonb, 14, :now, :now, :mentor, :application,
				    :now, 5, :now, :now)
				""").param("id", this.job).param("product", product).param("owner", this.lan).param("mentor", this.hung)
			.param("application", UUID.randomUUID()).param("now", now).update();
		String content = """
				{"scope":"Đánh giá","priceVnd":1500001,"codeJob":false,"assessment":true,"skills":[],"externalAccess":[],
				 "milestones":[{"id":"%s","title":"Báo cáo","priceVnd":%d,"dueOn":"2026-11-20","revisionRounds":%d,
				   "criteria":[{"id":"%s","statement":"Báo cáo nêu đủ chín mục","checkSteps":"Mở báo cáo, đếm mục"}]},
				  {"id":"%s","title":"Kế hoạch sửa","priceVnd":500000,"dueOn":"2026-12-01","revisionRounds":1,"criteria":[]}]}
				""".formatted(this.milestone, PRICE, revisionRounds, this.criterion, this.next);
		this.jdbc.sql("""
				INSERT INTO job.agreement_version (job_id, version_no, status, content, author_account_id, created_at)
				VALUES (:job, 1, 'SHARED', CAST(:content AS JSONB), :mentor, :now)
				""").param("job", this.job).param("content", content).param("mentor", this.hung).param("now", now).update();
		this.jdbc.sql("""
				INSERT INTO job.confirmed_agreement (job_id, version_no, draft_version_no, content, crew_usernames,
				    freeze_committed, customer_account_id, approved_at, mentor_account_id, confirmed_at)
				VALUES (:job, 1, 1, CAST(:content AS JSONB), '{}', false, :owner, :now, :mentor, :now)
				""").param("job", this.job).param("content", content).param("owner", this.lan).param("mentor", this.hung)
			.param("now", now).update();
		this.jdbc.sql("UPDATE job.job SET agreement_version_no = 1 WHERE id = :id").param("id", this.job).update();
		UUID row = UUID.randomUUID();
		this.jdbc.sql("""
				INSERT INTO job.crew_row (id, job_id, member_account_id, status, signed_at, activated_at, created_at)
				VALUES (:id, :job, :student, 'ACTIVE', :signed, :signed, now())
				""").param("id", row).param("job", this.job).param("student", this.vy)
			.param("signed", Timestamp.from(this.clock.instant().minus(Duration.ofMinutes(5)))).update();
		this.jdbc.sql("""
				INSERT INTO job.crew_terms_version (job_id, version_no, status, cap_percent, author_account_id, created_at)
				VALUES (:job, 1, 'IN_FORCE', 100, :mentor, now())
				""").param("job", this.job).param("mentor", this.hung).update();
		this.jdbc.sql("""
				INSERT INTO job.crew_terms_share (job_id, version_no, party_account_id, crew_row_id, percent)
				VALUES (:job, 1, :mentor, NULL, 70), (:job, 1, :vy, :row, 30)
				""").param("job", this.job).param("mentor", this.hung).param("vy", this.vy).param("row", row).update();
		this.jdbc.sql("""
				INSERT INTO job.milestone (id, job_id, position, title, price_vnd, revision_rounds, created_at, state, funded_at,
				    crew_terms_version_no)
				VALUES (:id, :job, 0, 'Báo cáo', :price, :rounds, now(), 'FUNDED', now(), 1)
				""").param("id", this.milestone).param("job", this.job).param("price", PRICE).param("rounds", revisionRounds)
			.update();
		this.jdbc.sql("""
				INSERT INTO job.milestone (id, job_id, position, title, price_vnd, revision_rounds, created_at)
				VALUES (:id, :job, 1, 'Kế hoạch sửa', 500000, 1, now())
				""").param("id", this.next).param("job", this.job).update();
		for (UUID account : List.of(this.milestone, this.next)) {
			this.jdbc.sql("""
					INSERT INTO payment.ledger_account (milestone_id, job_id, product_id, created_at) VALUES (:id, :job, :product, now())
					""").param("id", account).param("job", this.job).param("product", product).update();
		}
		this.jdbc.sql("""
				INSERT INTO payment.ledger_entry (id, operation_key, entry_type, bucket, job_id, product_id, milestone_id, amount_vnd,
				    vnp_transaction_no, created_at)
				VALUES (:id, :key, 'FUND', 'MILESTONE', :job, :product, :milestone, :amount, :no, now())
				""").param("id", UUID.randomUUID()).param("key", "FUND:" + UUID.randomUUID()).param("job", this.job)
			.param("product", product).param("milestone", this.milestone).param("amount", PRICE)
			.param("no", String.valueOf(ThreadLocalRandom.current().nextLong(1, Long.MAX_VALUE))).update();
		this.jdbc.sql("""
				INSERT INTO work.board (job_id, product_id, code, next_number, created_at) VALUES (:job, :product, 'BCD', 2, :now)
				""").param("job", this.job).param("product", product).param("now", now).update();
		UUID story = UUID.randomUUID();
		this.jdbc.sql("""
				INSERT INTO work.work_item (id, job_id, number, type, title, description, criterion_id, milestone_id, status,
				    created_at, updated_at)
				VALUES (:id, :job, 1, 'STORY', 'Báo cáo nêu đủ chín mục', '', :criterion, :milestone, 'DONE', now(), now())
				""").param("id", story).param("job", this.job).param("criterion", this.criterion).param("milestone", this.milestone)
			.update();
		this.jdbc.sql("""
				INSERT INTO work.work_item_history (id, work_item_id, job_id, actor_account_id, action, changes, comment, at)
				VALUES (:id, :item, :job, :mentor, 'APPROVED', '{}'::jsonb, 'Đạt yêu cầu', :now)
				""").param("id", UUID.randomUUID()).param("item", story).param("job", this.job).param("mentor", this.hung)
			.param("now", now).update();
		this.mvc.perform(post("/api/v1/boards/" + this.job + "/pages").with(as(this.hung, Role.MENTOR)).with(csrf())
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"title\":\"Báo cáo đánh giá\",\"body\":\"## Hiện trạng\\n\\nỔn.\",\"template\":\"ASSESSMENT_REPORT\"}"))
			.andExpect(status().isCreated());
	}

	private String handOver(long version) throws Exception {
		String json = "{\"milestoneId\":\"" + this.milestone + "\",\"version\":" + version + ",\"criteria\":[{\"criterionId\":\""
				+ this.criterion + "\",\"met\":true,\"note\":\"Đã rà đủ chín mục.\"}]}";
		return JsonPath.read(this.mvc.perform(post("/api/v1/boards/" + this.job + "/handovers").with(as(this.hung, Role.MENTOR))
			.with(csrf())
			.contentType(MediaType.APPLICATION_JSON)
			.content(json)).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(), "$.id");
	}

	private ResultActions decide(UUID account, Role role, String handover, String action, long version, boolean met,
			String comment, String sensitive) throws Exception {
		String json = "{\"version\":" + version + ",\"checks\":[{\"criterionId\":\"" + this.criterion + "\",\"met\":" + met
				+ (comment == null ? "" : ",\"comment\":\"" + comment + "\"") + "}]"
				+ (sensitive == null ? "" : ",\"sensitive\":\"" + sensitive + "\"") + "}";
		return this.mvc.perform(post("/api/v1/handovers/" + handover + "/" + action).with(as(account, role)).with(csrf())
			.contentType(MediaType.APPLICATION_JSON)
			.content(json));
	}

	private long count(String sql) {
		return this.jdbc.sql(sql).query(Long.class).single();
	}

	private String text(String sql) {
		return this.jdbc.sql(sql).query(String.class).single();
	}

	private long notifications(String type) {
		return this.jdbc.sql("SELECT count(*) FROM notification.notification WHERE type = :type").param("type", type)
			.query(Long.class).single();
	}

	private long openTodos(String key, UUID account) {
		return this.jdbc.sql("SELECT count(*) FROM notification.todo WHERE todo_key = :key AND account_id = :id AND closed_at IS NULL")
			.param("key", key).param("id", account).query(Long.class).single();
	}

	/** A GitHub token shape built at run time, so no credential-shaped literal sits in the source. */
	private static String fakeToken() {
		String alphabet = "Q7mZ2xK9pL4vB8nR3tY6wC1dF5gH0jSaEuIoNcXbVk";
		StringBuilder token = new StringBuilder("ghp").append('_');
		for (int i = 0; i < 36; i++) {
			token.append(alphabet.charAt((i * 7 + 3) % alphabet.length()));
		}
		return token.toString();
	}

	private RequestPostProcessor as(UUID account, Role role) {
		return IdentityTestSupport.signedIn(this.sessions, account, role, this.clock.instant());
	}

}
