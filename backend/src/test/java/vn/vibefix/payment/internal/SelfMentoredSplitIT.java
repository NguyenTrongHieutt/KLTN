package vn.vibefix.payment.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
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

import vn.vibefix.identity.internal.IdentityTestSupport;
import vn.vibefix.identity.internal.Role;
import vn.vibefix.job.MilestoneSplits;
import vn.vibefix.job.MilestoneSplits.PercentLine;
import vn.vibefix.job.MilestoneSplits.PreviewBasis;
import vn.vibefix.shared.ErasureBlocker;
import vn.vibefix.shared.PersonalDataParticipant;
import vn.vibefix.support.IntegrationTest;
import vn.vibefix.support.TestPeople;
import vn.vibefix.timers.internal.DeadlinePoller;

/**
 * Story 25.6 (FR-42, FR-83, FR-84, AD-15, AD-29, AD-33, AD-40, AD-47 exception 4; the money check of 2026-11-28, M-01 to
 * M-13): in a Self-mentored Job the whole Milestone is split among the Crew members who were active on it. The Customer,
 * who is the Mentor there, drafts the split and is never a party, so no entry pays the Customer from the Customer's own
 * payment; the remainder goes to the member who signed first; one member alone is paid at once; a release with no party
 * is refused; and a normal Job's split is as it was.
 */
class SelfMentoredSplitIT extends IntegrationTest {

	private static final ZoneId VIETNAM = ZoneId.of("Asia/Ho_Chi_Minh");

	/** One dong above a round number, so that every split leaves a remainder. */
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
	private MilestoneSplits splits;

	@Autowired
	private LedgerCheck ledgerCheck;

	@Autowired
	private List<PersonalDataParticipant> participants;

	private UUID lan;

	private UUID hung;

	private UUID vy;

	private UUID minh;

	private UUID job;

	private UUID milestone;

	private UUID criterion;

	@Test
	void theMembersSplitTheWholeMilestoneAndTheCustomerDraftsWithoutBeingAParty() throws Exception {
		world(true);
		UUID split = accepted();

		assertThat(text("SELECT self_mentored || ':' || cap_applies || ':' || status FROM job.milestone_split WHERE id = '"
				+ split + "'")).isEqualTo("true:false:AWAITING_DRAFT");
		assertThat(this.jdbc.sql("SELECT mentor_account_id FROM job.milestone_split WHERE id = :id").param("id", split)
			.query(UUID.class).single()).as("the drafter").isEqualTo(this.lan);
		// Minh signed first, so Minh is party 0, though Vy's Crew row is the older one.
		assertThat(this.jdbc.sql("SELECT party_account_id FROM job.milestone_split_party WHERE split_id = :id ORDER BY position")
			.param("id", split).query(UUID.class).list()).containsExactly(this.minh, this.vy);
		assertThat(count("SELECT count(*) FROM job.milestone_split_party WHERE split_id = '" + split + "' AND is_mentor")).isZero();
		assertThat(openTodos("job.draft_split", this.lan)).isOne();
		assertThat(text("SELECT role FROM notification.todo WHERE todo_key = 'job.draft_split'")).isEqualTo("CUSTOMER_MENTOR");
		assertThat(notices("job.split_pending", this.vy)).isOne();
		assertThat(notices("job.split_pending", this.lan)).as("the drafter is no party").isZero();

		// The Customer reads the split they draft; a member reads it as a party.
		this.mvc.perform(get("/api/v1/jobs/" + this.job + "/splits").with(as(this.lan))).andExpect(status().isOk())
			.andExpect(jsonPath("$.length()").value(1))
			.andExpect(jsonPath("$[0].selfMentored").value(true))
			.andExpect(jsonPath("$[0].viewerIsMentor").value(true))
			.andExpect(jsonPath("$[0].capApplies").value(false))
			.andExpect(jsonPath("$[0].cap").doesNotExist())
			.andExpect(jsonPath("$[0].pooledVnd").value(PRICE))
			.andExpect(jsonPath("$[0].n").value(2))
			.andExpect(jsonPath("$[0].parties.length()").value(2))
			.andExpect(jsonPath("$[0].parties[?(@.mentor == true)]").isEmpty())
			.andExpect(jsonPath("$[0].remainderPartyId").value(party(split, this.minh).toString()));
		this.mvc.perform(get("/api/v1/jobs/" + this.job + "/splits").with(as(this.vy))).andExpect(status().isOk())
			.andExpect(jsonPath("$[0].viewerIsMentor").value(false));

		// The floor holds without the cap: a member of the whole Milestone gets more than 0.
		save(this.lan, split, 0, 0, 100).andExpect(status().isBadRequest()).andExpect(jsonPath("$.reason").value("SPLIT_INVALID"));
		save(this.vy, split, 0, 66, 34).andExpect(status().isForbidden()).andExpect(jsonPath("$.reason").value("MENTOR_ONLY"));
		this.mvc.perform(post("/api/v1/splits/" + split + "/preview").with(as(this.lan)).with(csrf())
			.contentType(MediaType.APPLICATION_JSON).content(shares(split, 66, 34))).andExpect(status().isOk())
			.andExpect(jsonPath("$.pooledVnd").value(PRICE))
			.andExpect(jsonPath("$.lines[0].amountVnd").value(660_001))
			.andExpect(jsonPath("$.lines[1].amountVnd").value(340_000));
		save(this.lan, split, 0, 66, 34).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("PROPOSED"));
		assertThat(count("SELECT count(*) FROM job.milestone_split_version WHERE split_id = '" + split
				+ "' AND cap_percent IS NOT NULL")).as("no cap is stored").isZero();
		assertThat(openTodos("job.confirm_split", this.vy)).isOne();
		assertThat(openTodos("job.confirm_split", this.lan)).as("the drafter confirms nothing").isZero();

		// An open split keeps its drafter's account, as it keeps a party's.
		assertThat(blockers(this.lan)).contains("SPLIT_PENDING");

		confirm(this.lan, split, 1).andExpect(status().isForbidden()).andExpect(jsonPath("$.reason").value("PARTY_ONLY"));
		confirm(this.hung, split, 1).andExpect(status().isNotFound());
		confirm(this.vy, split, 1).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("PROPOSED"));
		confirm(this.minh, split, 1).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("FINAL"));
		awaitListeners();

		// The whole pool reached the members, the remainder with the one who signed first, and nothing the Customer.
		assertThat(release(this.minh)).isEqualTo(660_001);
		assertThat(release(this.vy)).isEqualTo(340_000);
		assertThat(count("SELECT count(*) FROM payment.ledger_entry WHERE entry_type = 'RELEASE' AND party_account_id = '" + this.lan
				+ "'")).as("no entry credits the Customer from the Job's pool").isZero();
		assertThat(count("SELECT coalesce(sum(amount_vnd), 0) FROM payment.ledger_entry WHERE entry_type = 'RELEASE'"))
			.isEqualTo(PRICE);
		assertThat(count("SELECT count(*) FROM payment.ledger_entry WHERE entry_type = 'POOL_RELEASED'")).isOne();
		assertThat(notices("payment.funds_released", this.minh)).isOne();
		assertThat(notices("payment.funds_released", this.vy)).isOne();
		assertThat(notices("payment.funds_released", this.lan)).isZero();
		assertThat(notices("job.split_final", this.lan)).as("the drafter hears how it ended").isOne();
		assertThat(this.ledgerCheck.check()).isEmpty();
		assertThat(blockers(this.lan)).doesNotContain("SPLIT_PENDING");

		// The Customer's Balance holds no Mentor-side figure for the Job; the Mentor's funding view names each member's part.
		this.mvc.perform(get("/api/v1/balance").with(as(this.lan))).andExpect(status().isOk())
			.andExpect(jsonPath("$.balanceVnd").value(0))
			.andExpect(jsonPath("$.lines.length()").value(0))
			.andExpect(jsonPath("$.heldVnd").doesNotExist())
			.andExpect(jsonPath("$.sentToCrewVnd").doesNotExist());
		this.mvc.perform(get("/api/v1/mentor/jobs/" + this.job + "/funding").with(as(this.lan))).andExpect(status().isOk())
			.andExpect(jsonPath("$.milestones[0].releases.length()").value(2))
			.andExpect(jsonPath("$.milestones[0].releases[?(@.role == 'MENTOR')]").isEmpty())
			.andExpect(jsonPath("$.milestones[0].releases[?(@.name == 'Lê Quang Minh')].amountVnd").value(660_001));
	}

	@Test
	void oneMemberAloneIsPaidTheWholeMilestoneAtOnce() throws Exception {
		world(true);
		// Minh left before the Milestone was funded: Vy is its one party.
		this.jdbc.sql("""
				UPDATE job.crew_row SET status = 'RELEASED', released_reason = 'LEFT', released_at = :end, window_end = :end
				WHERE job_id = :job AND member_account_id = :minh
				""").param("end", Timestamp.from(this.clock.instant().minus(Duration.ofMinutes(3)))).param("job", this.job)
			.param("minh", this.minh).update();

		UUID split = accepted();
		awaitListeners();

		assertThat(text("SELECT status || ':' || finality FROM job.milestone_split WHERE id = '" + split + "'"))
			.isEqualTo("FINAL:SOLE_MEMBER");
		assertThat(text("SELECT kind FROM job.milestone_split_version WHERE split_id = '" + split + "'")).isEqualTo("SOLE_MEMBER");
		assertThat(release(this.vy)).as("the whole Milestone, remainder included").isEqualTo(PRICE);
		assertThat(count("SELECT count(*) FROM payment.ledger_entry WHERE entry_type = 'RELEASE'")).isOne();
		assertThat(count("SELECT count(*) FROM notification.todo WHERE todo_key IN ('job.draft_split', 'job.confirm_split')"))
			.as("no draft and no confirmation").isZero();
		assertThat(count("SELECT count(*) FROM timers.deadline WHERE subject_id = '" + split + "'")).isZero();
		assertThat(count("SELECT count(*) FROM notification.notification WHERE type LIKE 'job.split_%'")).isZero();
		assertThat(notices("payment.funds_released", this.vy)).isOne();
		this.mvc.perform(get("/api/v1/jobs/" + this.job + "/splits").with(as(this.lan))).andExpect(status().isOk())
			.andExpect(jsonPath("$[0].finality").value("SOLE_MEMBER"))
			.andExpect(jsonPath("$[0].paid").value(true))
			.andExpect(jsonPath("$[0].latest.kind").value("SOLE_MEMBER"))
			.andExpect(jsonPath("$[0].latest.lines[0].amountVnd").value(PRICE));
		assertThat(this.ledgerCheck.check()).isEmpty();
	}

	/** R58: equal percentages, the leftover point and the rounding remainder to the earliest commitment signature. */
	@Test
	void withNoDraftTheDefaultSplitsEquallyInTheOrderOfTheSignatures() throws Exception {
		world(true);
		UUID an = TestPeople.student(this.jdbc, "Phạm Hoài An", true, List.of(TestPeople.REACT), "React",
				LocalDate.parse("2026-09-30"));
		member(an, Duration.ofMinutes(4));
		UUID split = accepted();

		this.clock.advance(Duration.ofDays(7).plusMinutes(1));
		this.poller.runDue();
		awaitListeners();

		assertThat(text("SELECT kind || ':' || (cap_percent IS NULL) FROM job.milestone_split_version WHERE split_id = '" + split
				+ "'")).isEqualTo("DEFAULT:true");
		assertThat(this.jdbc.sql("""
				SELECT s.percent FROM job.milestone_split_share s JOIN job.milestone_split_party p ON p.id = s.party_id
				WHERE s.split_id = :id ORDER BY p.position
				""").param("id", split).query(Integer.class).list()).as("Minh, Vy, An by their signatures").containsExactly(34, 33, 33);
		assertThat(notices("job.split_defaulted", this.lan)).isOne();
		save(this.lan, split, 1, 40, 30, 30).andExpect(status().isUnprocessableEntity())
			.andExpect(jsonPath("$.reason").value("SPLIT_DRAFT_LATE"));

		this.clock.advance(Duration.ofDays(3).plusMinutes(1));
		this.poller.runDue();
		awaitListeners();

		assertThat(text("SELECT status || ':' || finality FROM job.milestone_split WHERE id = '" + split + "'"))
			.isEqualTo("FINAL:DEADLINE");
		assertThat(release(this.minh)).as("34% and the one dong left over").isEqualTo(340_001);
		assertThat(release(this.vy)).isEqualTo(330_000);
		assertThat(release(an)).isEqualTo(330_000);
		assertThat(this.ledgerCheck.check()).isEmpty();
	}

	/** AD-29, AD-31, M-01: with no member active since the funding, nothing can be released, by either release. */
	@Test
	void aMilestoneNoMemberWorkedOnHasNoPartyAndNothingIsReleasedOnIt() throws Exception {
		world(true);
		assertThat(this.splits.hasWouldBeSplitParty(this.milestone)).isTrue();

		this.jdbc.sql("""
				UPDATE job.crew_row SET status = 'RELEASED', released_reason = 'LEFT', released_at = :end, window_end = :end WHERE job_id = :job
				""").param("end", Timestamp.from(this.clock.instant().minus(Duration.ofMinutes(3)))).param("job", this.job).update();

		assertThat(this.splits.hasWouldBeSplitParty(this.milestone)).isFalse();
		// A handover seeded as waiting for a decision, though no member could have made it: the acceptance is refused in
		// the guard, before any write.
		String handover = seededHandover();
		decide(this.lan, handover, 6).andExpect(status().isUnprocessableEntity())
			.andExpect(jsonPath("$.reason").value("NO_SPLIT_PARTY"));
		assertThat(text("SELECT state FROM job.milestone WHERE id = '" + this.milestone + "'")).isEqualTo("SUBMITTED");
		assertThat(count("SELECT count(*) FROM job.milestone_split")).isZero();
		assertThat(count("SELECT count(*) FROM payment.ledger_entry WHERE entry_type <> 'FUND'")).isZero();
	}

	@Test
	void aNormalJobKeepsItsMentorAsPartyZeroAndItsCustomerReadsNoSplit() throws Exception {
		world(false);
		UUID split = accepted();

		assertThat(text("SELECT self_mentored || ':' || cap_applies FROM job.milestone_split WHERE id = '" + split + "'"))
			.isEqualTo("false:true");
		assertThat(this.jdbc.sql("""
				SELECT party_account_id FROM job.milestone_split_party WHERE split_id = :id AND is_mentor AND position = 0
				""").param("id", split).query(UUID.class).single()).isEqualTo(this.hung);
		this.mvc.perform(get("/api/v1/jobs/" + this.job + "/splits").with(as(this.lan))).andExpect(status().isNotFound());
		this.mvc.perform(get("/api/v1/jobs/" + this.job + "/splits").with(as(this.hung))).andExpect(status().isOk())
			.andExpect(jsonPath("$[0].selfMentored").value(false))
			.andExpect(jsonPath("$[0].viewerIsMentor").value(true))
			.andExpect(jsonPath("$[0].remainderPartyId").value(party(split, this.hung).toString()));
		confirm(this.lan, split, 1).andExpect(status().isNotFound());
		assertThat(this.splits.hasWouldBeSplitParty(this.milestone)).isTrue();
	}

	/** The schema's backstop (M-10): no party row names the Job's Customer, and a mode split has no Mentor row. */
	@Test
	void noPartyRowEverNamesTheCustomer() throws Exception {
		world(true);
		UUID split = accepted();

		assertThatThrownBy(() -> partyRow(split, this.lan, false, 7)).hasMessageContaining("never a party");
		assertThatThrownBy(() -> partyRow(split, this.hung, true, 8)).hasMessageContaining("no Mentor party");

		// SG-23's daily check is the ledger's own backstop: a RELEASE that pays the Job's Customer is listed, however it
		// got there.
		assertThat(this.ledgerCheck.check()).isEmpty();
		UUID entry = UUID.randomUUID();
		this.jdbc.sql("""
				INSERT INTO payment.ledger_entry (id, operation_key, entry_type, job_id, product_id, milestone_id, amount_vnd,
				    party_account_id, split_version_no, share_percent, base_amount_vnd, created_at)
				SELECT :id, :key, 'RELEASE', job_id, product_id, milestone_id, 1, :customer, 1, 1, :base, now()
				FROM payment.ledger_account WHERE milestone_id = :milestone
				""").param("id", entry).param("key", "RELEASE:" + split + ":" + this.lan).param("customer", this.lan)
			.param("base", PRICE).param("milestone", this.milestone).update();
		assertThat(this.ledgerCheck.check()).contains(entry);
	}

	/**
	 * M-02: no finalization reads the drafter. A split whose drafter an erasure anonymized, which the open split's
	 * blocker prevents and this test forces, still becomes final and is paid out to its parties.
	 */
	@Test
	void aSplitWhoseDrafterIsGoneStillBecomesFinalAndIsPaid() throws Exception {
		world(true);
		UUID split = accepted();
		save(this.lan, split, 0, 50, 50).andExpect(status().isOk());
		this.jdbc.sql("UPDATE job.milestone_split SET mentor_account_id = NULL WHERE id = :id").param("id", split).update();

		confirm(this.vy, split, 1).andExpect(status().isOk());
		confirm(this.minh, split, 1).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("FINAL"));
		awaitListeners();

		assertThat(release(this.minh)).isEqualTo(500_001);
		assertThat(release(this.vy)).isEqualTo(500_000);
		assertThat(count("SELECT count(*) FROM payment.ledger_entry WHERE entry_type = 'POOL_RELEASED'")).isOne();
		assertThat(this.splits.finalSplitOf(this.milestone)).as("FR-64's retry needs no drafter either").isPresent();
		this.mvc.perform(get("/api/v1/jobs/" + this.job + "/splits").with(as(this.lan))).andExpect(status().isOk())
			.andExpect(jsonPath("$.length()").value(0));
	}

	/** FR-84, AD-40: any party asks for a Split Resolution; the Customer drafts, states once, and neither raises nor withdraws. */
	@Test
	void aPartyAsksForASplitResolutionAndTheCustomerStatesAsTheDrafter() throws Exception {
		world(true);
		UUID split = accepted();
		save(this.lan, split, 0, 99, 1).andExpect(status().isOk());

		raise(this.lan, split).andExpect(status().isForbidden()).andExpect(jsonPath("$.reason").value("PARTY_ONLY"));
		// Minh is party 0, the remainder receiver, and no Mentor: Minh may raise too. Vy does.
		String resolution = JsonPath.read(raise(this.vy, split).andExpect(status().isCreated()).andReturn().getResponse()
			.getContentAsString(), "$.resolutions[0].id");
		awaitListeners();

		assertThat(text("SELECT status FROM job.milestone_split WHERE id = '" + split + "'")).isEqualTo("IN_RESOLUTION");
		assertThat(notices("dispute.split_resolution_requested", this.lan)).as("the drafter is told as the Mentor is").isOne();
		assertThat(notices("dispute.split_resolution_requested", this.minh)).isOne();
		assertThat(notices("dispute.split_resolution_requested", this.vy)).as("the raiser").isZero();
		this.mvc.perform(get("/api/v1/splits/" + split + "/resolutions").with(as(this.lan))).andExpect(status().isOk())
			.andExpect(jsonPath("$.viewerMayRaise").value(false))
			.andExpect(jsonPath("$.resolutions[0].viewerIsRaiser").value(false))
			.andExpect(jsonPath("$.resolutions[0].viewerMayState").value(true))
			.andExpect(jsonPath("$.resolutions[0].reason").value("Vy làm cả giai đoạn mà chỉ được 1%"));

		state(this.lan, resolution).andExpect(status().isCreated())
			.andExpect(jsonPath("$.resolutions[0].viewerMayState").value(false))
			.andExpect(jsonPath("$.resolutions[0].statements[0].mentor").value(true))
			.andExpect(jsonPath("$.resolutions[0].statements[0].partyId").doesNotExist());
		state(this.lan, resolution).andExpect(status().isUnprocessableEntity())
			.andExpect(jsonPath("$.reason").value("SPLIT_STATEMENT_USED"));
		state(this.minh, resolution).andExpect(status().isCreated())
			.andExpect(jsonPath("$.resolutions[0].statements.length()").value(2));
		this.mvc.perform(post("/api/v1/split-resolutions/" + resolution + "/withdraw").with(as(this.lan)).with(csrf()))
			.andExpect(status().isForbidden());

		// The Administrator's decision takes the remainder receiver from the split and checks no cap, and keeps the floor.
		UUID minhParty = party(split, this.minh);
		UUID vyParty = party(split, this.vy);
		PreviewBasis basis = this.splits.decisionBasis(split, List.of(new PercentLine(minhParty, BigDecimal.valueOf(50)),
				new PercentLine(vyParty, BigDecimal.valueOf(50))));
		assertThat(basis.remainderPartyId()).isEqualTo(minhParty);
		assertThatThrownBy(() -> this.splits.decisionBasis(split, List.of(new PercentLine(minhParty, BigDecimal.valueOf(100)),
				new PercentLine(vyParty, BigDecimal.ZERO)))).hasMessageContaining("SPLIT_INVALID");

		this.mvc.perform(post("/api/v1/split-resolutions/" + resolution + "/withdraw").with(as(this.vy)).with(csrf()))
			.andExpect(status().isOk()).andExpect(jsonPath("$.splitStatus").value("PROPOSED"));
	}

	/**
	 * An Active Assessment Job with Milestone 1 Funded at {@code PRICE} one minute ago, and on its Crew Vy, who signed five
	 * minutes ago, and Minh, whose Crew row is newer and who signed ten minutes ago. Self-mentored, Lan is its Mentor too;
	 * else Hùng is.
	 */
	private void world(boolean selfMentored) {
		this.lan = IdentityTestSupport.insertAccount(this.jdbc, Role.CUSTOMER, null);
		this.hung = TestPeople.mentor(this.jdbc, "Đỗ Mạnh Hùng", List.of(TestPeople.REACT), "React");
		this.vy = TestPeople.student(this.jdbc, "Trần Thảo Vy", true, List.of(TestPeople.REACT), "React",
				LocalDate.parse("2026-09-30"));
		this.minh = TestPeople.student(this.jdbc, "Lê Quang Minh", true, List.of(TestPeople.REACT), "React",
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
		this.criterion = UUID.randomUUID();
		Timestamp now = Timestamp.from(this.clock.instant());
		LocalDate today = LocalDate.ofInstant(this.clock.instant(), VIETNAM);
		this.jdbc.sql("""
				INSERT INTO job.job (id, product_id, customer_account_id, state, title, problem, expected, app_users,
				    job_types, budget, deadline, rules, selection_days, opened_at, lapses_at, mentor_account_id,
				    selected_application_id, selected_at, version, created_at, updated_at)
				VALUES (:id, :product, :owner, 'ACTIVE', 'Đánh giá sổ đặt bàn', 'Lỗi', 'Đúng', 'Khách', ARRAY['ASSESSMENT'],
				    'UNDER_2M', DATE '2026-12-01', CAST('{"AUTO_ACCEPTANCE_DAYS":7}' AS JSONB), 14, :now, :now, :mentor,
				    :application, :now, 5, :now, :now)
				""").param("id", this.job).param("product", product).param("owner", this.lan).param("mentor", this.hung)
			.param("application", UUID.randomUUID()).param("now", now).update();
		if (selfMentored) {
			this.jdbc.sql("""
					UPDATE job.job SET self_mentored = TRUE, self_mentored_notice_version = '1.0',
					    mentor_account_id = customer_account_id, selected_application_id = NULL WHERE id = :id
					""").param("id", this.job).update();
		}
		UUID mentor = selfMentored ? this.lan : this.hung;
		String content = """
				{"scope":"Đánh giá","priceVnd":1000001,"codeJob":false,"assessment":true,"skills":[],"externalAccess":[],
				 "milestones":[{"id":"%s","title":"Báo cáo","priceVnd":%d,"dueOn":"%s","revisionRounds":2,
				   "criteria":[{"id":"%s","statement":"Báo cáo nêu đủ chín mục","checkSteps":"Mở báo cáo, đếm mục"}]}]}
				""".formatted(this.milestone, PRICE, today, this.criterion);
		this.jdbc.sql("""
				INSERT INTO job.agreement_version (job_id, version_no, status, content, author_account_id, created_at)
				VALUES (:job, 1, 'SHARED', CAST(:content AS JSONB), :mentor, :now)
				""").param("job", this.job).param("content", content).param("mentor", mentor).param("now", now).update();
		this.jdbc.sql("""
				INSERT INTO job.confirmed_agreement (job_id, version_no, draft_version_no, content, crew_usernames,
				    freeze_committed, customer_account_id, approved_at, mentor_account_id, confirmed_at)
				VALUES (:job, 1, 1, CAST(:content AS JSONB), '{}', false, :owner, :now, :mentor, :now)
				""").param("job", this.job).param("content", content).param("owner", this.lan).param("mentor", mentor)
			.param("now", now).update();
		this.jdbc.sql("UPDATE job.job SET agreement_version_no = 1 WHERE id = :id").param("id", this.job).update();
		member(this.vy, Duration.ofMinutes(5));
		member(this.minh, Duration.ofMinutes(10));
		this.jdbc.sql("""
				INSERT INTO job.milestone (id, job_id, position, title, price_vnd, revision_rounds, created_at, state, funded_at,
				    due_on)
				VALUES (:id, :job, 0, 'Báo cáo', :price, 2, now(), 'FUNDED', :funded, :due)
				""").param("id", this.milestone).param("job", this.job).param("price", PRICE)
			.param("funded", Timestamp.from(this.clock.instant().minus(Duration.ofMinutes(1)))).param("due", today)
			.update();
		this.jdbc.sql("""
				INSERT INTO payment.ledger_account (milestone_id, job_id, product_id, created_at) VALUES (:id, :job, :product, now())
				""").param("id", this.milestone).param("job", this.job).param("product", product).update();
		this.jdbc.sql("""
				INSERT INTO payment.ledger_entry (id, operation_key, entry_type, bucket, job_id, product_id, milestone_id, amount_vnd,
				    vnp_transaction_no, created_at)
				VALUES (:id, :key, 'FUND', 'MILESTONE', :job, :product, :milestone, :amount, :no, now())
				""").param("id", UUID.randomUUID()).param("key", "FUND:" + UUID.randomUUID()).param("job", this.job)
			.param("product", product).param("milestone", this.milestone).param("amount", PRICE)
			.param("no", String.valueOf(ThreadLocalRandom.current().nextLong(1, Long.MAX_VALUE))).update();
	}

	/** An {@code ACTIVE} Crew row whose commitment was signed {@code ago}; its row is as new as the call. */
	private void member(UUID student, Duration ago) {
		this.jdbc.sql("""
				INSERT INTO job.crew_row (id, job_id, member_account_id, status, signed_at, activated_at, created_at)
				VALUES (:id, :job, :student, 'ACTIVE', :signed, :signed, clock_timestamp())
				""").param("id", UUID.randomUUID()).param("job", this.job).param("student", student)
			.param("signed", Timestamp.from(this.clock.instant().minus(ago))).update();
	}

	/** Vy hands the Milestone over, in a normal Job Hùng does, and Lan accepts it: the release creates the split. */
	private UUID accepted() throws Exception {
		boolean selfMentored = this.jdbc.sql("SELECT self_mentored FROM job.job WHERE id = :id").param("id", this.job)
			.query(Boolean.class).single();
		String json = "{\"milestoneId\":\"" + this.milestone + "\",\"version\":5,\"criteria\":[{\"criterionId\":\"" + this.criterion
				+ "\",\"met\":true,\"note\":\"Đã rà đủ chín mục.\"}]"
				+ ",\"links\":[{\"kind\":\"DOCUMENT\",\"url\":\"https://docs.google.com/document/d/bao-cao\",\"label\":\"Báo cáo\"}]}";
		String handover = JsonPath.read(this.mvc
			.perform(post("/api/v1/boards/" + this.job + "/handovers").with(as(selfMentored ? this.vy : this.hung)).with(csrf())
				.contentType(MediaType.APPLICATION_JSON).content(json))
			.andExpect(status().isAccepted()).andReturn().getResponse().getContentAsString(), "$.handoverId");
		decide(this.lan, handover, 6).andExpect(status().isOk());
		awaitListeners();
		return this.jdbc.sql("SELECT id FROM job.milestone_split WHERE milestone_id = :id").param("id", this.milestone)
			.query(UUID.class).single();
	}

	private ResultActions decide(UUID account, String handover, long version) throws Exception {
		return this.mvc.perform(post("/api/v1/handovers/" + handover + "/accept").with(as(account)).with(csrf())
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"version\":" + version + ",\"checks\":[{\"criterionId\":\"" + this.criterion + "\",\"met\":true}]}"));
	}

	/**
	 * A handover waiting for the Customer's decision, written straight into the rows as if a member had handed over: the
	 * Milestone is Submitted at Job version 6.
	 */
	private String seededHandover() {
		UUID handover = UUID.randomUUID();
		Timestamp now = Timestamp.from(this.clock.instant());
		this.jdbc.sql("UPDATE job.milestone SET state = 'SUBMITTED', submitted_at = :at, handover_id = :handover WHERE id = :id")
			.param("at", now).param("handover", handover).param("id", this.milestone).update();
		this.jdbc.sql("UPDATE job.job SET version = 6 WHERE id = :id").param("id", this.job).update();
		UUID product = this.jdbc.sql("SELECT product_id FROM job.job WHERE id = :id").param("id", this.job).query(UUID.class)
			.single();
		this.jdbc.sql("""
				INSERT INTO delivery.handover (id, job_id, product_id, milestone_id, milestone_number, milestone_title, round,
				    mentor_account_id, status, job_version, transition_id, check_by, created_at)
				VALUES (:id, :job, :product, :milestone, 1, 'Báo cáo', 1, :member, 'SUBMITTED', 6, :transition, :at, :at)
				""").param("id", handover).param("job", this.job).param("product", product).param("milestone", this.milestone)
			.param("member", this.vy).param("transition", UUID.randomUUID()).param("at", now).update();
		this.jdbc.sql("""
				INSERT INTO delivery.handover_criterion (handover_id, criterion_id, number, statement, check_steps, met, note)
				VALUES (:handover, :criterion, 1, 'Báo cáo nêu đủ chín mục', 'Mở báo cáo, đếm mục', TRUE, 'Đã rà.')
				""").param("handover", handover).param("criterion", this.criterion).update();
		return handover.toString();
	}

	private ResultActions save(UUID account, UUID split, int shown, int... percents) throws Exception {
		return this.mvc.perform(post("/api/v1/splits/" + split + "/versions").with(as(account)).with(csrf())
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"shownVersionNo\":" + shown + "," + shares(split, percents).substring(1)));
	}

	/** The percentages in the order of the split's parties. */
	private String shares(UUID split, int... percents) {
		List<UUID> parties = this.jdbc.sql("SELECT id FROM job.milestone_split_party WHERE split_id = :id ORDER BY position")
			.param("id", split).query(UUID.class).list();
		StringBuilder json = new StringBuilder("{\"shares\":[");
		for (int i = 0; i < percents.length; i++) {
			json.append(i == 0 ? "" : ",").append("{\"partyId\":\"").append(parties.get(i)).append("\",\"percent\":")
				.append(percents[i]).append('}');
		}
		return json.append("]}").toString();
	}

	private ResultActions confirm(UUID account, UUID split, int version) throws Exception {
		return this.mvc.perform(post("/api/v1/splits/" + split + "/confirmations").with(as(account)).with(csrf())
			.contentType(MediaType.APPLICATION_JSON).content("{\"splitVersionNo\":" + version + "}"));
	}

	private ResultActions raise(UUID account, UUID split) throws Exception {
		return this.mvc.perform(post("/api/v1/splits/" + split + "/resolutions").with(as(account)).with(csrf())
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"splitVersionNo\":1,\"reason\":\"Vy làm cả giai đoạn mà chỉ được 1%\"}"));
	}

	private ResultActions state(UUID account, String resolution) throws Exception {
		return this.mvc.perform(post("/api/v1/split-resolutions/" + resolution + "/statements").with(as(account)).with(csrf())
			.contentType(MediaType.APPLICATION_JSON).content("{\"text\":\"Tôi chia theo phần việc đã bàn giao.\"}"));
	}

	private UUID party(UUID split, UUID account) {
		return this.jdbc.sql("SELECT id FROM job.milestone_split_party WHERE split_id = :split AND party_account_id = :account")
			.param("split", split).param("account", account).query(UUID.class).single();
	}

	private void partyRow(UUID split, UUID account, boolean mentor, int position) {
		this.jdbc.sql("""
				INSERT INTO job.milestone_split_party (id, split_id, party_account_id, is_mentor, whole_interval, active_at_release,
				    position)
				VALUES (:id, :split, :account, :mentor, TRUE, TRUE, :position)
				""").param("id", UUID.randomUUID()).param("split", split).param("account", account).param("mentor", mentor)
			.param("position", position).update();
	}

	private long release(UUID account) {
		return this.jdbc.sql("""
				SELECT coalesce(sum(amount_vnd), 0) FROM payment.ledger_entry WHERE entry_type = 'RELEASE' AND party_account_id = :id
				""").param("id", account).query(Long.class).single();
	}

	private List<String> blockers(UUID account) {
		return this.participants.stream().flatMap(participant -> participant.erasureBlockers(account).stream())
			.map(ErasureBlocker::reason).toList();
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

	private RequestPostProcessor as(UUID account) {
		Role role = account.equals(this.lan) ? Role.CUSTOMER : account.equals(this.hung) ? Role.MENTOR : Role.STUDENT;
		return IdentityTestSupport.signedIn(this.sessions, account, role, this.clock.instant());
	}

}
