package vn.vibefix.payment.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.Timestamp;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.session.Session;
import org.springframework.session.SessionRepository;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.support.TransactionTemplate;

import vn.vibefix.authz.JobContent;
import vn.vibefix.identity.internal.IdentityTestSupport;
import vn.vibefix.identity.internal.Role;
import vn.vibefix.job.MilestoneHandovers;
import vn.vibefix.job.MilestoneSplitFinalized;
import vn.vibefix.job.MilestoneSplitFinalized.Finality;
import vn.vibefix.payment.Releases;
import vn.vibefix.shared.ErasureOutcome;
import vn.vibefix.shared.PersonalData;
import vn.vibefix.shared.PersonalDataSection;
import vn.vibefix.support.HeldCrewJob;
import vn.vibefix.support.IntegrationTest;

/**
 * Story 22.5 (FR-44, FR-45, NFR-11, AD-15, AD-20, AD-24, SG-31): a split's parties follow their money on MEN-7 and the
 * Mentor on JOB-7, while the Customer's surfaces never change. A party's line is pending while the pool is not reversed,
 * whatever the split's status, and the pool never counts in the Balance; once paid, each party reads their own part, the
 * split's Mentor every part, and nobody another person's line. The export lists the person's own ledger lines only. A
 * test finalizes a split by SQL and publishes its event, so the one executor pays it out ({@code SplitPoolLedgerIT}).
 */
class SplitHistoryIT extends IntegrationTest {

	@Autowired
	private JdbcClient jdbc;

	@Autowired
	private TransactionTemplate transactions;

	@Autowired
	private MilestoneHandovers handovers;

	@Autowired
	private Releases releases;

	@Autowired
	private ApplicationEventPublisher events;

	@Autowired
	private MockMvc mvc;

	@Autowired
	private SessionRepository<? extends Session> sessions;

	@Autowired
	private PersonalData personalData;

	private HeldCrewJob world;

	@BeforeEach
	void aHeldMilestoneOfACrewOfThree() {
		this.world = HeldCrewJob.create(this.jdbc, this.clock.instant());
	}

	// ---- Pending -------------------------------------------------------------------------------

	@Test
	void anOpenSplitShowsItsPartiesThePoolAsPendingAndNoBalance() throws Exception {
		accept();

		balance(this.world.vy()).andExpect(jsonPath("$.balanceVnd").value(0))
			.andExpect(jsonPath("$.releases").value(0))
			.andExpect(jsonPath("$.sentToCrewVnd").doesNotExist())
			.andExpect(jsonPath("$.lines.length()").value(1))
			.andExpect(jsonPath("$.lines[0].jobId").value(this.world.job().toString()))
			.andExpect(jsonPath("$.lines[0].milestoneId").value(this.world.milestone().toString()))
			.andExpect(jsonPath("$.lines[0].jobTitle").value("Sửa lỗi đặt bàn"))
			.andExpect(jsonPath("$.lines[0].jobLive").value(true))
			.andExpect(jsonPath("$.lines[0].number").value(1))
			.andExpect(jsonPath("$.lines[0].role").value("CREW"))
			.andExpect(jsonPath("$.lines[0].status").value("AWAITING_DRAFT"))
			.andExpect(jsonPath("$.lines[0].pending").value(true))
			.andExpect(jsonPath("$.lines[0].baseAmountVnd").value(HeldCrewJob.PRICE))
			.andExpect(jsonPath("$.lines[0].yourPartVnd").value(0))
			.andExpect(jsonPath("$.lines[0].yourPercent").doesNotExist())
			.andExpect(jsonPath("$.lines[0].crew.length()").value(0));
		balance(this.world.hung()).andExpect(jsonPath("$.balanceVnd").value(0))
			.andExpect(jsonPath("$.lines[0].role").value("MENTOR"))
			.andExpect(jsonPath("$.lines[0].pending").value(true))
			.andExpect(jsonPath("$.lines[0].crew.length()").value(0))
			.andExpect(jsonPath("$.sentToCrewVnd").value(0));

		split("PROPOSED");
		balance(this.world.minh()).andExpect(jsonPath("$.lines[0].status").value("PROPOSED"))
			.andExpect(jsonPath("$.lines[0].pending").value(true));
		split("IN_RESOLUTION");
		balance(this.world.minh()).andExpect(jsonPath("$.lines[0].status").value("IN_RESOLUTION"))
			.andExpect(jsonPath("$.lines[0].pending").value(true))
			.andExpect(jsonPath("$.lines[0].baseAmountVnd").value(HeldCrewJob.PRICE));

		// Nobody else reads it: not the Customer, not someone of no Job.
		String customer = balance(this.world.lan()).andExpect(jsonPath("$.lines.length()").value(0))
			.andReturn().getResponse().getContentAsString();
		assertThat(customer).doesNotContain("IN_RESOLUTION").doesNotContain(this.world.milestone().toString());
		balance(stranger()).andExpect(jsonPath("$.lines.length()").value(0));
	}

	@Test
	void theMentorsJob7ShowsTheSplitPendingUntilItPaysOut() throws Exception {
		accept();

		mentorFunding().andExpect(jsonPath("$.milestones[0].splitStatus").value("AWAITING_DRAFT"))
			.andExpect(jsonPath("$.milestones[0].pooledVnd").value(HeldCrewJob.PRICE))
			.andExpect(jsonPath("$.milestones[0].paid").value(false))
			.andExpect(jsonPath("$.milestones[0].finality").doesNotExist())
			.andExpect(jsonPath("$.milestones[0].releases.length()").value(0))
			.andExpect(jsonPath("$.milestones[0].releasedTotalVnd").value(HeldCrewJob.PRICE));

		finalize(percentages(30, 40, 30));

		mentorFunding().andExpect(jsonPath("$.milestones[0].splitStatus").value("FINAL"))
			.andExpect(jsonPath("$.milestones[0].finality").value("CONFIRMED"))
			.andExpect(jsonPath("$.milestones[0].finalAt").exists())
			.andExpect(jsonPath("$.milestones[0].paid").value(true))
			.andExpect(jsonPath("$.milestones[0].releases.length()").value(3))
			.andExpect(jsonPath("$.milestones[0].releases[?(@.amountVnd == 400000)].name").value("Trần Thảo Vy"))
			.andExpect(jsonPath("$.milestones[0].releases[*].erased").value(everyItem(is(false))));
		// A party is no Mentor of the Job: JOB-7's Assignee read stays the Mentor's, and the Customer's stays hers.
		this.mvc.perform(get("/api/v1/mentor/jobs/" + this.world.job() + "/funding").with(as(this.world.vy(), Role.STUDENT)))
			.andExpect(status().isNotFound());
		this.mvc.perform(get("/api/v1/jobs/" + this.world.job() + "/funding").with(as(this.world.vy(), Role.STUDENT)))
			.andExpect(status().isNotFound());
	}

	/** D3: a final split waiting for the listener, or refused, keeps its money on the screen. */
	@Test
	void aFinalSplitNotYetPaidStaysPending() throws Exception {
		accept();
		finalizeBySql(splitId(), percentages(30, 40, 30));

		balance(this.world.vy()).andExpect(jsonPath("$.balanceVnd").value(0))
			.andExpect(jsonPath("$.lines[0].status").value("FINAL"))
			.andExpect(jsonPath("$.lines[0].finality").value("CONFIRMED"))
			.andExpect(jsonPath("$.lines[0].pending").value(true))
			.andExpect(jsonPath("$.lines[0].baseAmountVnd").value(HeldCrewJob.PRICE))
			.andExpect(jsonPath("$.lines[0].yourPartVnd").value(0));
		mentorFunding().andExpect(jsonPath("$.milestones[0].splitStatus").value("FINAL"))
			.andExpect(jsonPath("$.milestones[0].paid").value(false));
	}

	// ---- Paid ----------------------------------------------------------------------------------

	@Test
	void aPaidSplitShowsEachPartyTheirOwnPartAndTheMentorEveryPart() throws Exception {
		accept();
		finalize(percentages(30, 70, 0));

		String vy = balance(this.world.vy()).andExpect(jsonPath("$.balanceVnd").value(700_000))
			.andExpect(jsonPath("$.releases").value(1))
			.andExpect(jsonPath("$.lines.length()").value(1))
			.andExpect(jsonPath("$.lines[0].status").value("FINAL"))
			.andExpect(jsonPath("$.lines[0].finality").value("CONFIRMED"))
			.andExpect(jsonPath("$.lines[0].finalAt").exists())
			.andExpect(jsonPath("$.lines[0].pending").value(false))
			.andExpect(jsonPath("$.lines[0].role").value("CREW"))
			.andExpect(jsonPath("$.lines[0].yourPartVnd").value(700_000))
			.andExpect(jsonPath("$.lines[0].yourPercent").value(70))
			.andExpect(jsonPath("$.lines[0].splitVersionNo").value(1))
			.andExpect(jsonPath("$.lines[0].baseAmountVnd").value(HeldCrewJob.PRICE))
			.andExpect(jsonPath("$.lines[0].crew.length()").value(0))
			.andReturn().getResponse().getContentAsString();
		assertThat(vy).as("the Mentor's part is not hers to read").doesNotContain("300000");
		// D5: a party at 0% has a line at 0 with the split's final state, and no Balance.
		balance(this.world.minh()).andExpect(jsonPath("$.balanceVnd").value(0))
			.andExpect(jsonPath("$.releases").value(0))
			.andExpect(jsonPath("$.lines.length()").value(1))
			.andExpect(jsonPath("$.lines[0].status").value("FINAL"))
			.andExpect(jsonPath("$.lines[0].pending").value(false))
			.andExpect(jsonPath("$.lines[0].yourPartVnd").value(0))
			.andExpect(jsonPath("$.lines[0].yourPercent").value(0))
			.andExpect(jsonPath("$.lines[0].crew.length()").value(0));
		balance(this.world.hung()).andExpect(jsonPath("$.balanceVnd").value(300_000))
			.andExpect(jsonPath("$.lines[0].role").value("MENTOR"))
			.andExpect(jsonPath("$.lines[0].crew.length()").value(1))
			.andExpect(jsonPath("$.lines[0].crew[0].name").value("Trần Thảo Vy"))
			.andExpect(jsonPath("$.lines[0].crew[0].erased").value(false))
			.andExpect(jsonPath("$.lines[0].crew[0].amountVnd").value(700_000))
			.andExpect(jsonPath("$.sentToCrewVnd").value(700_000));
	}

	@Test
	void aMentorAtZeroPercentKeepsEveryPartOfThatMilestone() throws Exception {
		accept();
		finalize(percentages(0, 60, 40));

		balance(this.world.hung()).andExpect(jsonPath("$.balanceVnd").value(0))
			.andExpect(jsonPath("$.releases").value(0))
			.andExpect(jsonPath("$.lines[0].role").value("MENTOR"))
			.andExpect(jsonPath("$.lines[0].yourPartVnd").value(0))
			.andExpect(jsonPath("$.lines[0].yourPercent").value(0))
			.andExpect(jsonPath("$.lines[0].crew.length()").value(2))
			.andExpect(jsonPath("$.sentToCrewVnd").value(HeldCrewJob.PRICE));
	}

	/** Former members read by account, whatever their {@code window_end} says. */
	@Test
	void aMemberWhoLeftReadsTheirLineOpenAndPaid() throws Exception {
		accept();
		this.jdbc.sql("""
				UPDATE job.crew_row SET status = 'RELEASED', released_reason = 'LEFT', released_at = :at, window_end = :at
				WHERE job_id = :job AND member_account_id = :member
				""").param("at", Timestamp.from(this.clock.instant().minus(Duration.ofDays(2)))).param("job", this.world.job())
			.param("member", this.world.minh()).update();

		balance(this.world.minh()).andExpect(jsonPath("$.lines[0].pending").value(true))
			.andExpect(jsonPath("$.lines[0].status").value("AWAITING_DRAFT"));
		finalize(percentages(30, 40, 30));
		balance(this.world.minh()).andExpect(jsonPath("$.balanceVnd").value(300_000))
			.andExpect(jsonPath("$.lines[0].yourPartVnd").value(300_000));
	}

	/** A Mentor of one Job who is a Crew party of another reads that Job's line as a Crew member: their part alone. */
	@Test
	void theMentorOfOneJobReadsAnotherJobsSplitAsItsCrew() throws Exception {
		HeldCrewJob other = HeldCrewJob.create(this.jdbc, this.clock.instant());
		UUID split = other.split(this.jdbc, this.clock.instant(), other.milestone(), this.world.hung());
		JobContent.pool(this.jdbc, other.job(), other.product(), other.milestone(), HeldCrewJob.PRICE, this.clock.instant());
		Map<UUID, Integer> percentages = new LinkedHashMap<>();
		percentages.put(other.hung(), 40);
		percentages.put(this.world.hung(), 60);
		finalizeBySql(split, percentages);
		publish(new MilestoneSplitFinalized(UUID.randomUUID(), split, other.job(), other.product(), other.milestone(), 1,
				"Sửa lỗi đặt bàn", other.hung(), 1, percentages, Finality.CONFIRMED));
		awaitPaid(other.milestone());

		String body = balance(this.world.hung()).andExpect(jsonPath("$.balanceVnd").value(600_000))
			.andExpect(jsonPath("$.lines.length()").value(1))
			.andExpect(jsonPath("$.lines[0].jobId").value(other.job().toString()))
			.andExpect(jsonPath("$.lines[0].role").value("CREW"))
			.andExpect(jsonPath("$.lines[0].crew.length()").value(0))
			.andReturn().getResponse().getContentAsString();
		assertThat(body).as("the other Job's Mentor's part").doesNotContain("400000");
		balance(other.hung()).andExpect(jsonPath("$.lines[0].role").value("MENTOR"))
			.andExpect(jsonPath("$.lines[0].crew[0].amountVnd").value(600_000));
		this.mvc.perform(get("/api/v1/mentor/jobs/" + other.job() + "/funding").with(as(this.world.hung(), Role.MENTOR)))
			.andExpect(status().isNotFound());
	}

	// ---- Gone and erased (AD-24) ---------------------------------------------------------------

	/** D6: a pending line keeps its Job, whose JOB-10 stays readable, though the Job has no title or screens any more. */
	@Test
	void aPendingLineOfADeletedProductKeepsItsJob() throws Exception {
		accept();
		close();

		this.transactions.executeWithoutResult(status -> this.personalData.deleteProduct(this.world.product()));

		balance(this.world.vy()).andExpect(jsonPath("$.lines.length()").value(1))
			.andExpect(jsonPath("$.lines[0].jobId").value(this.world.job().toString()))
			.andExpect(jsonPath("$.lines[0].jobTitle").value(""))
			.andExpect(jsonPath("$.lines[0].jobLive").value(false))
			.andExpect(jsonPath("$.lines[0].number").value(1))
			.andExpect(jsonPath("$.lines[0].milestoneTitle").value(""))
			.andExpect(jsonPath("$.lines[0].pending").value(true))
			.andExpect(jsonPath("$.lines[0].baseAmountVnd").value(HeldCrewJob.PRICE));
		this.mvc.perform(get("/api/v1/jobs/" + this.world.job() + "/splits").with(as(this.world.vy(), Role.STUDENT)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$[0].pooledVnd").value(HeldCrewJob.PRICE));
	}

	@Test
	void anErasedPartyReadsAsErasedAndTheirPartStays() throws Exception {
		accept();
		finalize(percentages(20, 40, 40));
		close();

		ErasureOutcome outcome = this.transactions.execute(status -> this.personalData.erase(this.world.minh()));
		assertThat(outcome).isInstanceOf(ErasureOutcome.Erased.class);

		balance(this.world.hung()).andExpect(jsonPath("$.lines[0].crew.length()").value(2))
			.andExpect(jsonPath("$.lines[0].crew[?(@.erased == true)].amountVnd").value(400_000))
			.andExpect(jsonPath("$.lines[0].crew[?(@.erased == true)].name").value(""))
			.andExpect(jsonPath("$.lines[0].crew[?(@.erased == false)].name").value("Trần Thảo Vy"));
		mentorFunding().andExpect(jsonPath("$.milestones[0].releases[?(@.erased == true)].amountVnd").value(400_000));
	}

	// ---- The payment history and the export ----------------------------------------------------

	/** "Lịch sử thanh toán" reads both: the Customer's fundings of one Job and the parts of another, each its own DTO. */
	@Test
	void theCustomerOfOneJobWhoIsAPartyOfAnotherReadsEachInItsOwnRole() throws Exception {
		HeldCrewJob other = HeldCrewJob.create(this.jdbc, this.clock.instant());
		for (String where : List.of("job.job WHERE id = :job", "payment.payment_order WHERE job_id = :job")) {
			this.jdbc.sql("UPDATE " + where.replace(" WHERE", " SET customer_account_id = :vy WHERE"))
				.param("vy", this.world.vy())
				.param("job", other.job())
				.update();
		}
		accept();

		String history = this.mvc.perform(get("/api/v1/payments/history").with(as(this.world.vy(), Role.STUDENT)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.jobs.length()").value(1))
			.andExpect(jsonPath("$.jobs[0].jobId").value(other.job().toString()))
			.andExpect(jsonPath("$.jobs[0].rows[0].kind").value("FUNDING"))
			.andReturn().getResponse().getContentAsString();
		assertThat(history).as("CUS-10's DTO names no split").doesNotContain("AWAITING_DRAFT").doesNotContain(this.world.job().toString());
		balance(this.world.vy()).andExpect(jsonPath("$.lines.length()").value(1))
			.andExpect(jsonPath("$.lines[0].jobId").value(this.world.job().toString()))
			.andExpect(jsonPath("$.lines[0].role").value("CREW"));
	}

	@Test
	@SuppressWarnings("unchecked")
	void anExportListsThePersonsOwnLedgerLinesOnly() throws Exception {
		accept();
		finalize(percentages(20, 40, 40));
		UUID order = this.jdbc.sql("SELECT id FROM payment.payment_order").query(UUID.class).single();
		this.jdbc.sql("""
				INSERT INTO payment.ledger_entry (id, operation_key, entry_type, bucket, job_id, product_id, milestone_id,
				    payment_order_id, amount_vnd, created_at)
				SELECT :id, :key, 'REFUND_REQUESTED', bucket, job_id, product_id, milestone_id, payment_order_id, 1, now()
				FROM payment.ledger_entry WHERE entry_type = 'FUND' AND payment_order_id = :order
				""").param("id", UUID.randomUUID()).param("key", "REFUND_REQUESTED:" + UUID.randomUUID()).param("order", order).update();

		List<Map<String, Object>> vy = (List<Map<String, Object>>) payment(this.world.vy()).data().get("ledgerLines");
		assertThat(vy).extracting(line -> line.get("type") + ":" + line.get("amountVnd") + ":" + line.get("percent"))
			.containsExactly("RELEASE:400000:40");
		assertThat(payment(this.world.vy()).data()).doesNotContainKey("paymentOrders");
		List<Map<String, Object>> lan = (List<Map<String, Object>>) payment(this.world.lan()).data().get("ledgerLines");
		assertThat(lan).extracting(line -> line.get("type") + ":" + line.get("amountVnd"))
			.containsExactly("FUND:" + HeldCrewJob.PRICE, "REFUND_REQUESTED:1");
		assertThat(lan).allSatisfy(line -> assertThat(line.get("paymentOrderReference")).isNotNull());
		for (UUID person : List.of(this.world.vy(), this.world.lan(), this.world.hung())) {
			assertThat(payment(person).toString()).as("no pool is anybody's").doesNotContain("RELEASE_POOLED")
				.doesNotContain("POOL_RELEASED");
		}
		assertThat(payment(this.world.hung()).toString()).as("the Crew's parts are not the Mentor's export")
			.doesNotContain("400000");
	}

	// ---- Helpers --------------------------------------------------------------------------------

	/** M5 as {@code delivery} applies it, then the pool, in one transaction: the split waits for its draft. */
	private void accept() {
		this.transactions.executeWithoutResult(status -> {
			UUID handover = UUID.randomUUID();
			this.jdbc.sql("UPDATE job.milestone SET handover_id = :handover, state = 'SUBMITTED', submitted_at = :now WHERE id = :id")
				.param("handover", handover)
				.param("now", Timestamp.from(this.clock.instant()))
				.param("id", this.world.milestone())
				.update();
			UUID transition = this.handovers.autoAccept(this.world.job(), this.world.milestone(), handover).orElseThrow()
				.transitionId();
			this.releases.release(this.world.job(), this.world.milestone(), transition);
		});
	}

	/** The split's status, as Story 22.2's and 22.3's commands leave it. */
	private void split(String status) {
		this.jdbc.sql("""
				UPDATE job.milestone_split SET status = :status, confirmation_round = 1, confirmation_due_at = :due WHERE id = :id
				""").param("status", status).param("due", Timestamp.from(this.clock.instant().plus(Duration.ofDays(3))))
			.param("id", splitId()).update();
	}

	/** The Job closed and its Crew released, so an erasure or a Product deletion may go ahead. */
	private void close() {
		Timestamp now = Timestamp.from(this.clock.instant());
		this.jdbc.sql("UPDATE job.job SET state = 'CLOSED', closed_at = :now WHERE id = :id").param("now", now)
			.param("id", this.world.job()).update();
		this.jdbc.sql("""
				UPDATE job.crew_row SET status = 'RELEASED', released_reason = 'JOB_ENDED', released_at = :now, window_end = :now
				WHERE job_id = :id
				""").param("now", now).param("id", this.world.job()).update();
	}

	private Map<UUID, Integer> percentages(int mentor, int vy, int minh) {
		Map<UUID, Integer> percentages = new LinkedHashMap<>();
		percentages.put(this.world.hung(), mentor);
		percentages.put(this.world.vy(), vy);
		percentages.put(this.world.minh(), minh);
		return percentages;
	}

	private UUID splitId() {
		return this.jdbc.sql("SELECT id FROM job.milestone_split WHERE milestone_id = :id")
			.param("id", this.world.milestone())
			.query(UUID.class)
			.single();
	}

	/** What Story 22.2 does, then the one executor's payout. */
	private void finalize(Map<UUID, Integer> percentages) {
		UUID split = splitId();
		finalizeBySql(split, percentages);
		publish(new MilestoneSplitFinalized(UUID.randomUUID(), split, this.world.job(), this.world.product(), this.world.milestone(),
				1, "Sửa lỗi đặt bàn", this.world.hung(), 1, percentages, Finality.CONFIRMED));
		awaitPaid(this.world.milestone());
	}

	private void finalizeBySql(UUID split, Map<UUID, Integer> percentages) {
		this.jdbc.sql("""
				INSERT INTO job.milestone_split_version (split_id, version_no, kind, cap_percent, author_account_id, created_at)
				SELECT :split, 1, 'DRAFT', 30, mentor_account_id, :now FROM job.milestone_split WHERE id = :split
				""").param("split", split).param("now", Timestamp.from(this.clock.instant())).update();
		percentages.forEach((party, percent) -> this.jdbc.sql("""
				INSERT INTO job.milestone_split_share (split_id, version_no, party_id, percent)
				SELECT :split, 1, id, :percent FROM job.milestone_split_party WHERE split_id = :split AND party_account_id = :party
				""").param("split", split).param("percent", percent).param("party", party).update());
		this.jdbc.sql("""
				UPDATE job.milestone_split SET status = 'FINAL', final_version_no = 1, final_at = :now, finality = 'CONFIRMED'
				WHERE id = :split
				""").param("now", Timestamp.from(this.clock.instant())).param("split", split).update();
	}

	private void publish(MilestoneSplitFinalized event) {
		this.transactions.executeWithoutResult(status -> this.events.publishEvent(event));
	}

	private void awaitPaid(UUID milestone) {
		await().atMost(Duration.ofSeconds(15))
			.until(() -> this.jdbc.sql("""
					SELECT count(*) FROM payment.ledger_entry WHERE entry_type = 'POOL_RELEASED' AND milestone_id = :id
					""").param("id", milestone).query(Long.class).single() == 1);
	}

	private ResultActions balance(UUID account) throws Exception {
		return this.mvc.perform(get("/api/v1/balance").with(as(account, null))).andExpect(status().isOk());
	}

	private ResultActions mentorFunding() throws Exception {
		return this.mvc.perform(get("/api/v1/mentor/jobs/" + this.world.job() + "/funding").with(as(this.world.hung(), Role.MENTOR)))
			.andExpect(status().isOk());
	}

	private PersonalDataSection payment(UUID account) {
		return this.transactions.execute(status -> this.personalData.export(account))
			.stream()
			.filter(section -> "payment".equals(section.module()))
			.findFirst()
			.orElseThrow();
	}

	private UUID stranger() {
		return IdentityTestSupport.insertGoogleAccount(this.jdbc, "google-stranger", "3.1");
	}

	private RequestPostProcessor as(UUID account, Role role) {
		return IdentityTestSupport.signedIn(this.sessions, account, role, this.clock.instant());
	}

}
