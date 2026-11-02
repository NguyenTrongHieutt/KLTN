package vn.vibefix.payment.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.session.Session;
import org.springframework.session.SessionRepository;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.support.TransactionTemplate;

import vn.vibefix.identity.internal.AdministratorTestSupport;
import vn.vibefix.identity.internal.IdentityTestSupport;
import vn.vibefix.identity.internal.Role;
import vn.vibefix.job.MilestoneHandovers;
import vn.vibefix.job.MilestoneSettlements;
import vn.vibefix.job.MilestoneSplitFinalized;
import vn.vibefix.job.MilestoneSplitFinalized.Finality;
import vn.vibefix.job.MilestoneSplits;
import vn.vibefix.job.MilestoneSplits.SplitParty;
import vn.vibefix.operations.FailedOperations;
import vn.vibefix.operations.Lane;
import vn.vibefix.operations.internal.OperationWorker;
import vn.vibefix.payment.Ledger;
import vn.vibefix.payment.Releases;
import vn.vibefix.shared.PersonalData;
import vn.vibefix.support.HeldCrewJob;
import vn.vibefix.support.IntegrationTest;

/**
 * Story 22.1 (FR-42, NFR-4, AD-15, AD-20, AD-29, AD-41, SM-6): the review's §4 ledger fixtures, each keeping the sums;
 * who a release fixes as a party and with which flags; the one executor of a final split with its refusals; the daily
 * check; and what a Customer never sees. A test finalizes a Crew split by SQL and publishes the event until Story 22.2
 * brings the path that does.
 */
class SplitPoolLedgerIT extends IntegrationTest {

	@Autowired
	private JdbcClient jdbc;

	@Autowired
	private TransactionTemplate transactions;

	@Autowired
	private MilestoneSettlements settlements;

	@Autowired
	private MilestoneHandovers handovers;

	@Autowired
	private MilestoneSplits splits;

	@Autowired
	private Releases releases;

	@Autowired
	private Ledger ledger;

	@Autowired
	private JdbcLedger entries;

	@Autowired
	private LedgerCheck check;

	@Autowired
	private ApplicationEventPublisher events;

	@Autowired
	private OperationWorker worker;

	@Autowired
	private FailedOperations failed;

	@Autowired
	private MockMvc mvc;

	@Autowired
	private SessionRepository<? extends Session> sessions;

	@Autowired
	private ApplicationContext context;

	@Autowired
	private PersonalData personalData;

	@Autowired
	private PaymentOrders orders;

	@Autowired
	private Refunds refunds;

	private HeldCrewJob world;

	private UUID milestone;

	@BeforeEach
	void aHeldMilestoneOfACrewOfThree() {
		this.world = HeldCrewJob.create(this.jdbc, this.clock.instant());
		this.milestone = this.world.milestone();
	}

	// ---- The release ----------------------------------------------------------------------------

	@Test
	void anAcceptanceAt10000001HoldsItAllInOnePoolAndPaysNobody() throws Exception {
		price(10_000_001);
		UUID transition = accept();

		assertThat(ledgerRows()).containsExactly("FUND:10000001", "RELEASE_POOLED:10000001");
		assertThat(text("SELECT operation_key FROM payment.ledger_entry WHERE entry_type = 'RELEASE_POOLED'"))
			.isEqualTo("RELEASE_POOLED:" + transition);
		assertThat(text("SELECT status || ':' || cap_applies FROM job.milestone_split WHERE milestone_id = '" + this.milestone + "'"))
			.isEqualTo("AWAITING_DRAFT:true");
		assertThat(parties()).containsExactly(new SplitParty(this.world.hung(), true, true, true),
				new SplitParty(this.world.vy(), false, true, true), new SplitParty(this.world.minh(), false, true, true));
		assertThat(positions()).containsExactly(0, 1, 2);
		assertThat(this.ledger.releasedTotal(this.milestone)).isEqualTo(10_000_001);
		assertThat(this.ledger.releasable(this.milestone)).isZero();
		assertThat(LedgerMath.balanced(this.entries.entries(this.milestone))).isTrue();
		assertThat(count("SELECT count(*) FROM notification.notification WHERE type = 'payment.funds_released'")).isZero();
		assertThat(count("SELECT count(*) FROM job.milestone_split_version")).isZero();
		// A split write moves no version the Customer or the Crew compares (AD-29).
		assertThat(count("SELECT crew_version FROM job.job WHERE id = '" + this.world.job() + "'")).isZero();
	}

	@Test
	void theDraftDeadlineFollowsTheJobsCopiedRule() throws Exception {
		this.jdbc.sql("""
				UPDATE job.job SET rules = rules || '{"SPLIT_DRAFT_DAYS": 9}'::jsonb WHERE id = :id
				""").param("id", this.world.job()).update();
		accept();

		assertThat(this.jdbc.sql("SELECT draft_due_at FROM job.milestone_split").query(Timestamp.class).single().toInstant())
			.isEqualTo(this.clock.instant().plus(Duration.ofDays(9)));
	}

	@Test
	void aSettlementOf6000000And4000001HoldsTheReleaseAndRefundsTheRest() throws Exception {
		price(10_000_001);
		settle(6_000_000, 4_000_001);

		assertThat(ledgerRows()).containsExactly("FUND:10000001", "REFUND_REQUESTED:4000001", "RELEASE_POOLED:6000000");
		assertThat(text("SELECT status FROM job.milestone_split")).isEqualTo("AWAITING_DRAFT");
		assertThat(this.ledger.releasedTotal(this.milestone)).isEqualTo(6_000_000);
		assertThat(this.ledger.releasable(this.milestone)).isZero();
		assertThat(LedgerMath.balanced(this.entries.entries(this.milestone))).isTrue();
	}

	@Test
	void aSettlementWithNoReleaseMakesNoPoolAndNoSplit() throws Exception {
		settle(0, HeldCrewJob.PRICE);

		assertThat(ledgerRows()).containsExactly("FUND:" + HeldCrewJob.PRICE, "REFUND_REQUESTED:" + HeldCrewJob.PRICE);
		assertThat(count("SELECT count(*) FROM job.milestone_split")).isZero();
		assertThat(LedgerMath.balanced(this.entries.entries(this.milestone))).isTrue();
	}

	@Test
	void aPriceCutPoolsTheNewPriceAndRefundsTheDifference() throws Exception {
		price(10_000_001);
		this.jdbc.sql("UPDATE job.milestone SET price_vnd = 8000000 WHERE id = :id").param("id", this.milestone).update();
		accept();

		assertThat(ledgerRows()).containsExactly("FUND:10000001", "REFUND_REQUESTED:2000001", "RELEASE_POOLED:8000000");
		assertThat(text("SELECT reason FROM payment.refund")).isEqualTo("PRICE_CUT");
		assertThat(this.ledger.releasable(this.milestone)).isZero();
		assertThat(LedgerMath.balanced(this.entries.entries(this.milestone))).isTrue();
	}

	@Test
	void aSettlementThatDoesNotSumIsRefusedAndWritesNothing() {
		assertThatThrownBy(() -> this.transactions.executeWithoutResult(status -> {
			UUID cause = this.settlements.settle(this.world.job(), this.milestone, 500_000, 400_000);
			this.releases.settle(this.world.job(), this.milestone, cause, 500_000, 400_000);
		})).isInstanceOf(IllegalStateException.class).hasMessageContaining("does not sum");

		assertThat(ledgerRows()).containsExactly("FUND:" + HeldCrewJob.PRICE);
		assertThat(count("SELECT count(*) FROM job.milestone_split")).as("the transition is refused whole").isZero();
		assertThat(text("SELECT state FROM job.milestone WHERE id = '" + this.milestone + "'")).isEqualTo("FUNDED");
	}

	@Test
	void aSecondPoolOnAMilestoneIsRefusedByTheIndex() {
		this.transactions.executeWithoutResult(status -> this.releases.release(this.world.job(), this.milestone, UUID.randomUUID()));

		assertThatThrownBy(() -> this.jdbc.sql("""
				INSERT INTO payment.ledger_entry (id, operation_key, entry_type, job_id, product_id, milestone_id, amount_vnd, created_at)
				VALUES (:id, :key, 'RELEASE_POOLED', :job, :product, :milestone, 1, now())
				""")
			.param("id", UUID.randomUUID())
			.param("key", "RELEASE_POOLED:" + UUID.randomUUID())
			.param("job", this.world.job())
			.param("product", this.world.product())
			.param("milestone", this.milestone)
			.update()).isInstanceOf(DuplicateKeyException.class).hasMessageContaining("ledger_entry_pool_idx");
	}

	// ---- The parties ----------------------------------------------------------------------------

	@Test
	void aMemberWhoLeftDuringTheMilestoneIsAPartyNoLongerActive() throws Exception {
		release(this.world.minh(), this.clock.instant().minus(Duration.ofHours(1)));
		accept();

		assertThat(parties()).containsExactly(new SplitParty(this.world.hung(), true, true, true),
				new SplitParty(this.world.vy(), false, true, true), new SplitParty(this.world.minh(), false, false, false));
	}

	@Test
	void aMemberWhoLeftBeforeTheMilestoneWasFundedIsNoParty() throws Exception {
		release(this.world.minh(), this.clock.instant().minus(Duration.ofDays(2)));
		accept();

		assertThat(accounts()).containsExactly(this.world.hung(), this.world.vy());
	}

	@Test
	void theBoundsOfTheIntervalAreInclusive() throws Exception {
		Instant funded = this.clock.instant().minus(Duration.ofDays(1));
		release(this.world.minh(), funded);
		accept();

		assertThat(accounts()).as("left at the very funding").containsExactly(this.world.hung(), this.world.vy(), this.world.minh());
		assertThat(parties().get(2).wholeInterval()).isFalse();
	}

	@Test
	void aMemberWhoSignsWhileTheMilestoneIsHeldIsAPartyForPartOfIt() throws Exception {
		Instant signed = this.clock.instant().minus(Duration.ofHours(2));
		this.jdbc.sql("""
				UPDATE job.crew_row SET signed_at = :signed, activated_at = :signed WHERE member_account_id = :minh AND job_id = :job
				""").param("signed", Timestamp.from(signed)).param("minh", this.world.minh()).param("job", this.world.job()).update();
		accept();

		assertThat(parties().get(2)).isEqualTo(new SplitParty(this.world.minh(), false, false, true));
	}

	@Test
	void aLockedMembersRowEndsAtTheLockAndAloneTheCapDoesNotApply() throws Exception {
		release(this.world.minh(), this.clock.instant().minus(Duration.ofDays(2)));
		Instant lockedAt = this.clock.instant().minus(Duration.ofHours(3));
		lock(this.world.vy(), lockedAt);
		accept();

		assertThat(parties()).containsExactly(new SplitParty(this.world.hung(), true, true, true),
				new SplitParty(this.world.vy(), false, false, false));
		assertThat(text("SELECT status || ':' || cap_applies FROM job.milestone_split")).isEqualTo("AWAITING_DRAFT:false");
	}

	/** Deferred from Story 20.4 (E2): a Milestone funded after a member's lock has no such party. */
	@Test
	void aMemberLockedBeforeTheFundingIsNoParty() throws Exception {
		release(this.world.minh(), this.clock.instant().minus(Duration.ofDays(2)));
		lock(this.world.vy(), this.clock.instant().minus(Duration.ofDays(2)));
		accept();

		await().atMost(Duration.ofSeconds(15)).until(() -> count("SELECT count(*) FROM payment.ledger_entry WHERE entry_type = 'RELEASE'") == 1);
		assertThat(accounts()).containsExactly(this.world.hung());
	}

	// ---- The one executor -----------------------------------------------------------------------

	@Test
	void aSplitWithTheMentorAloneIsFinalAtOnceAndPaysTheMentor() throws Exception {
		release(this.world.vy(), this.clock.instant().minus(Duration.ofDays(2)));
		release(this.world.minh(), this.clock.instant().minus(Duration.ofDays(2)));
		accept();

		await().atMost(Duration.ofSeconds(15)).until(() -> count("SELECT count(*) FROM payment.ledger_entry WHERE entry_type = 'POOL_RELEASED'") == 1);
		assertThat(text("SELECT status || ':' || final_version_no || ':' || cap_applies FROM job.milestone_split")).isEqualTo("FINAL:1:false");
		assertThat(text("SELECT version_no || ':' || kind || ':' || (author_account_id IS NULL) || ':' || (cap_percent IS NULL)"
				+ " FROM job.milestone_split_version")).isEqualTo("1:MENTOR_ONLY:true:true");
		assertThat(count("SELECT percent FROM job.milestone_split_share")).isEqualTo(100);
		assertThat(ledgerRows()).containsExactly("FUND:" + HeldCrewJob.PRICE, "POOL_RELEASED:" + HeldCrewJob.PRICE, "RELEASE:" + HeldCrewJob.PRICE,
				"RELEASE_POOLED:" + HeldCrewJob.PRICE);
		assertThat(text("SELECT party_account_id || ':' || split_version_no || ':' || share_percent || ':' || base_amount_vnd"
				+ " FROM payment.ledger_entry WHERE entry_type = 'RELEASE'"))
			.isEqualTo(this.world.hung() + ":1:100:" + HeldCrewJob.PRICE);
		assertThat(notices(this.world.hung())).isOne();
		this.mvc.perform(get("/api/v1/balance").with(as(this.world.hung(), Role.MENTOR)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.balanceVnd").value(HeldCrewJob.PRICE));
		assertThat(this.ledger.releasable(this.milestone)).isZero();
		assertThat(LedgerMath.balanced(this.entries.entries(this.milestone))).isTrue();
		assertThat(this.check.check()).isEmpty();
	}

	@Test
	void aFinalSplit305020WithALeaverPaysEachPartAndKeepsTheSums() throws Exception {
		price(10_000_001);
		release(this.world.minh(), this.clock.instant().minus(Duration.ofHours(1)));
		accept();
		long released = this.ledger.releasedTotal(this.milestone);

		MilestoneSplitFinalized event = finalize(percentages(30, 50, 20));
		await().atMost(Duration.ofSeconds(15)).until(() -> count("SELECT count(*) FROM payment.ledger_entry WHERE entry_type = 'POOL_RELEASED'") == 1);

		assertThat(partOf(this.world.hung())).isEqualTo(3_000_001);
		assertThat(partOf(this.world.vy())).isEqualTo(5_000_000);
		assertThat(partOf(this.world.minh())).as("a leaver still gets their part").isEqualTo(2_000_000);
		assertThat(text("SELECT operation_key FROM payment.ledger_entry WHERE entry_type = 'RELEASE' AND party_account_id = '"
				+ this.world.vy() + "'")).isEqualTo("RELEASE:" + event.milestoneSplitId() + ":" + this.world.vy());
		assertThat(text("SELECT split_version_no || ':' || share_percent || ':' || base_amount_vnd FROM payment.ledger_entry"
				+ " WHERE entry_type = 'RELEASE' AND party_account_id = '" + this.world.vy() + "'")).isEqualTo("1:50:10000001");
		assertThat(text("SELECT operation_key FROM payment.ledger_entry WHERE entry_type = 'POOL_RELEASED'"))
			.isEqualTo("POOL_RELEASED:" + text("SELECT id FROM payment.ledger_entry WHERE entry_type = 'RELEASE_POOLED'"));
		assertThat(this.ledger.releasedTotal(this.milestone)).as("a final split changes no total").isEqualTo(released);
		assertThat(this.ledger.releasable(this.milestone)).isZero();
		assertThat(LedgerMath.balanced(this.entries.entries(this.milestone))).isTrue();
		for (UUID payee : List.of(this.world.hung(), this.world.vy(), this.world.minh())) {
			assertThat(notices(payee)).as("each payee is told").isOne();
		}
		assertThat(notices(this.world.lan())).isZero();

		// A replay writes nothing.
		publish(event);
		awaitListeners();
		assertThat(count("SELECT count(*) FROM payment.ledger_entry WHERE entry_type = 'RELEASE'")).isEqualTo(3);
	}

	@Test
	void aZeroPartWritesNoEntry() throws Exception {
		accept();
		finalize(percentages(100, 0, 0));
		await().atMost(Duration.ofSeconds(15)).until(() -> count("SELECT count(*) FROM payment.ledger_entry WHERE entry_type = 'POOL_RELEASED'") == 1);

		assertThat(count("SELECT count(*) FROM payment.ledger_entry WHERE entry_type = 'RELEASE'")).isOne();
		assertThat(notices(this.world.vy())).isZero();
	}

	@Test
	void aFinalizationWithNoPoolWritesNothingAndLandsInTheFailedQueueOnce() throws Exception {
		MilestoneSplitFinalized event = event(UUID.randomUUID(), percentages(20, 40, 40));
		publish(event);
		refusedOnce("NO_POOL");

		publish(event);
		awaitListeners();
		assertThat(count("SELECT count(*) FROM operations.external_operation WHERE type = 'payment.split_pool_refused'"))
			.as("a replay writes nothing").isOne();
	}

	@Test
	void aFinalizationWhosePartiesDifferWritesNothing() throws Exception {
		accept();
		UUID split = splitId();
		publish(event(split, Map.of(this.world.hung(), 50, this.world.vy(), 50)));

		refusedOnce("PARTIES_DIFFER");
		assertThat(this.ledger.releasedTotal(this.milestone)).isEqualTo(HeldCrewJob.PRICE);
	}

	@Test
	void aFinalizationWhosePartsDoNotSumWritesNothing() throws Exception {
		accept();
		publish(event(splitId(), percentages(30, 50, 30)));

		refusedOnce("PARTS_OFF");
	}

	@Test
	void anAlertReachesAdministratorsThroughTheFailedQueue() throws Exception {
		UUID administrator = AdministratorTestSupport.create(this.context, "ha.phuong@vibefix.test", "Hà Phương").accountId();
		publish(event(UUID.randomUUID(), percentages(20, 40, 40)));
		awaitListeners();
		this.worker.runDue(Lane.VNPAY);

		await().atMost(Duration.ofSeconds(15)).until(() -> this.jdbc.sql("""
				SELECT count(*) FROM notification.notification WHERE account_id = :id
				""").param("id", administrator).query(Long.class).single() >= 1);
	}

	// ---- SG-23 ----------------------------------------------------------------------------------

	@Test
	void aPoolPastItsDueTimeIsListedOnceADayUnlessTheSplitIsInResolution() throws Exception {
		accept();
		UUID pool = UUID.fromString(text("SELECT id FROM payment.ledger_entry WHERE entry_type = 'RELEASE_POOLED'"));

		this.clock.advance(Duration.ofDays(14).minusMinutes(1));
		assertThat(this.check.check()).as("7 days after its 7-day draft deadline, not more").isEmpty();

		this.clock.advance(Duration.ofMinutes(2));
		assertThat(this.check.check()).containsExactly(pool);
		this.check.check();
		assertThat(count("SELECT count(*) FROM operations.external_operation WHERE type = 'payment.split_pool_overdue'"))
			.as("once a day").isOne();
		this.worker.runDue(Lane.VNPAY);
		assertThat(this.failed.findAll()).extracting(FailedOperations.FailedOperation::type, FailedOperations.FailedOperation::errorCode)
			.contains(org.assertj.core.groups.Tuple.tuple(SplitPoolOverdueOperation.TYPE, "SPLIT_POOL_OVERDUE"));

		this.clock.advance(Duration.ofDays(1));
		this.check.check();
		assertThat(count("SELECT count(*) FROM operations.external_operation WHERE type = 'payment.split_pool_overdue'"))
			.as("and again the next").isEqualTo(2);

		this.jdbc.sql("UPDATE job.milestone_split SET status = 'IN_RESOLUTION'").update();
		assertThat(this.check.check()).as("the undecided flag covers it").isEmpty();
	}

	@Test
	void aPoolWithNoSplitIsListed() {
		this.transactions.executeWithoutResult(status -> this.releases.release(this.world.job(), this.milestone, UUID.randomUUID()));

		assertThat(this.check.check()).containsExactly(UUID.fromString(text("SELECT id FROM payment.ledger_entry WHERE entry_type = 'RELEASE_POOLED'")));
	}

	@Test
	void aSplitFinalForMoreThanAnHourWithItsPoolUnreversedIsListed() throws Exception {
		accept();
		UUID split = splitId();
		finalizeBySql(split, percentages(20, 40, 40));

		this.clock.advance(Duration.ofMinutes(59));
		assertThat(this.check.check()).isEmpty();
		this.clock.advance(Duration.ofMinutes(2));
		assertThat(this.check.check()).containsExactly(split);
	}

	@Test
	void theCheckStillListsAFinalMilestoneThatDoesNotBalance() {
		this.jdbc.sql("UPDATE job.milestone SET state = 'CANCELLED' WHERE id = :id").param("id", this.milestone).update();

		assertThat(this.check.check()).containsExactly(this.milestone);
	}

	// ---- AD-20 ----------------------------------------------------------------------------------

	/** CUS-10, JOB-7 as Customer, notifications, and the Dispute decision view show the released total and nothing else. */
	@Test
	void noCustomerSurfaceShowsThePoolThePartsOrThatASplitIsPending() throws Exception {
		accept();
		this.mvc.perform(get("/api/v1/jobs/" + this.world.job() + "/funding").with(as(this.world.lan(), Role.CUSTOMER)))
			.andExpect(jsonPath("$.milestones[0].releasedTotalVnd").value(HeldCrewJob.PRICE));
		this.mvc.perform(get("/api/v1/payments/history").with(as(this.world.lan(), Role.CUSTOMER)))
			.andExpect(jsonPath("$.releasedVnd").value(HeldCrewJob.PRICE));
		awaitListeners();
		String pending = customerSurfaces();
		for (String secret : List.of("RELEASE_POOLED", "POOL_RELEASED", "AWAITING_DRAFT", "FINAL", splitId().toString(), "splitVersionNo")) {
			assertThat(pending).as(secret).doesNotContain(secret);
		}

		finalize(percentages(20, 40, 40));
		await().atMost(Duration.ofSeconds(15)).until(() -> count("SELECT count(*) FROM payment.ledger_entry WHERE entry_type = 'POOL_RELEASED'") == 1);

		assertThat(customerSurfaces()).as("nothing moves on her screens when the split becomes final").isEqualTo(pending);
		assertThat(notices(this.world.lan())).isZero();
	}

	private String customerSurfaces() throws Exception {
		StringBuilder bodies = new StringBuilder();
		for (String path : List.of("/api/v1/jobs/" + this.world.job() + "/funding", "/api/v1/payments/history", "/api/v1/notifications",
				"/api/v1/boards/" + this.world.job() + "/dispute")) {
			bodies.append(this.mvc.perform(get(path).with(as(this.world.lan(), Role.CUSTOMER)))
				.andReturn()
				.getResponse()
				.getContentAsString());
		}
		return bodies.toString();
	}

	// ---- What outlives its Job (AD-24) ----------------------------------------------------------

	/** The orders and refunds stay after their Product and their Customer: the money still moves, and nobody is told. */
	@Test
	void anOpenOrderAndARefundOutliveTheirJobAndTheirCustomerAndTellNobody() {
		UUID order = UUID.fromString(text("SELECT id::text FROM payment.payment_order"));
		this.jdbc.sql("UPDATE payment.payment_order SET status = 'OPEN', finished_at = NULL WHERE id = :id").param("id", order).update();
		UUID fund = UUID.fromString(text("SELECT id::text FROM payment.ledger_entry WHERE entry_type = 'FUND'"));
		this.transactions.executeWithoutResult(status -> this.personalData.deleteProduct(this.world.product()));
		this.jdbc.sql("UPDATE payment.payment_order SET customer_account_id = NULL WHERE id = :id").param("id", order).update();
		long told = count("SELECT count(*) FROM notification.notification");

		assertThat(this.transactions.<Boolean>execute(status -> this.orders.expire(order))).isEqualTo(Boolean.TRUE);
		assertThat(text("SELECT status FROM payment.payment_order")).isEqualTo("EXPIRED");

		this.transactions.executeWithoutResult(status -> this.refunds.request(fund, this.orders.byId(order).orElseThrow(), HeldCrewJob.PRICE,
				Refunds.Reason.ORDER_CLOSED));
		this.worker.runDue(Lane.VNPAY);

		assertThat(text("SELECT status || ':' || (customer_account_id IS NULL) FROM payment.refund")).isEqualTo("CONFIRMED:true");
		assertThat(count("SELECT count(*) FROM notification.notification")).isEqualTo(told);
	}

	// ---- Helpers --------------------------------------------------------------------------------

	/** M5 as {@code delivery} applies it on an Assessment Job, then the pool, in one transaction. */
	private UUID accept() {
		return this.transactions.execute(status -> {
			UUID handover = UUID.randomUUID();
			this.jdbc.sql("UPDATE job.milestone SET handover_id = :handover, state = 'SUBMITTED', submitted_at = :now WHERE id = :id")
				.param("handover", handover)
				.param("now", Timestamp.from(this.clock.instant()))
				.param("id", this.milestone)
				.update();
			UUID transition = this.handovers.autoAccept(this.world.job(), this.milestone, handover).orElseThrow().transitionId();
			this.releases.release(this.world.job(), this.milestone, transition);
			return transition;
		});
	}

	/** M8 as {@code dispute}'s execution applies it, then the pool and the refund, in one transaction. */
	private void settle(long releaseVnd, long refundVnd) {
		this.transactions.executeWithoutResult(status -> {
			UUID cause = this.settlements.settle(this.world.job(), this.milestone, releaseVnd, refundVnd);
			this.releases.settle(this.world.job(), this.milestone, cause, releaseVnd, refundVnd);
		});
	}

	/** The Milestone's price and its one FUND, at {@code amount}. */
	private void price(long amount) {
		this.jdbc.sql("UPDATE job.milestone SET price_vnd = :amount WHERE id = :id").param("amount", amount).param("id", this.milestone).update();
		this.jdbc.sql("UPDATE payment.payment_order SET amount_vnd = :amount WHERE milestone_id = :id")
			.param("amount", amount)
			.param("id", this.milestone)
			.update();
		this.jdbc.sql("DELETE FROM payment.ledger_entry WHERE entry_type = 'FUND' AND milestone_id = :id").param("id", this.milestone).update();
		this.jdbc.sql("""
				INSERT INTO payment.ledger_entry (id, operation_key, entry_type, bucket, job_id, product_id, milestone_id,
				    payment_order_id, amount_vnd, vnp_transaction_no, created_at)
				SELECT :entry, :key, 'FUND', 'MILESTONE', job_id, product_id, milestone_id, id, :amount, :no, now()
				FROM payment.payment_order WHERE milestone_id = :id
				""")
			.param("entry", UUID.randomUUID())
			.param("key", "FUND:" + UUID.randomUUID())
			.param("amount", amount)
			.param("no", UUID.randomUUID().toString())
			.param("id", this.milestone)
			.update();
	}

	private void release(UUID member, Instant end) {
		this.jdbc.sql("""
				UPDATE job.crew_row SET status = 'RELEASED', released_reason = 'LEFT', released_at = :end, window_end = :end
				WHERE job_id = :job AND member_account_id = :member
				""").param("end", Timestamp.from(end)).param("job", this.world.job()).param("member", member).update();
	}

	/** A lock whose listener has not run yet: the row is still {@code ACTIVE} under the Job lock (A-07). */
	private void lock(UUID account, Instant at) {
		this.jdbc.sql("UPDATE identity.account SET locked_at = :at, lock_reason = 'Vi phạm' WHERE id = :id")
			.param("at", Timestamp.from(at))
			.param("id", account)
			.update();
	}

	private Map<UUID, Integer> percentages(int mentor, int vy, int minh) {
		Map<UUID, Integer> percentages = new LinkedHashMap<>();
		percentages.put(this.world.hung(), mentor);
		percentages.put(this.world.vy(), vy);
		percentages.put(this.world.minh(), minh);
		return percentages;
	}

	private UUID splitId() {
		return this.jdbc.sql("SELECT id FROM job.milestone_split WHERE milestone_id = :id").param("id", this.milestone).query(UUID.class).single();
	}

	/** What Story 22.2 does: the split {@code FINAL} at a new version, and its event in the same transaction. */
	private MilestoneSplitFinalized finalize(Map<UUID, Integer> percentages) {
		UUID split = splitId();
		finalizeBySql(split, percentages);
		MilestoneSplitFinalized event = event(split, percentages);
		publish(event);
		return event;
	}

	private void finalizeBySql(UUID split, Map<UUID, Integer> percentages) {
		this.jdbc.sql("""
				INSERT INTO job.milestone_split_version (split_id, version_no, kind, cap_percent, author_account_id, created_at)
				VALUES (:split, 1, 'DRAFT', 30, :mentor, :now)
				""").param("split", split).param("mentor", this.world.hung()).param("now", Timestamp.from(this.clock.instant())).update();
		percentages.forEach((party, percent) -> this.jdbc.sql("""
				INSERT INTO job.milestone_split_share (split_id, version_no, party_id, percent)
				SELECT :split, 1, id, :percent FROM job.milestone_split_party WHERE split_id = :split AND party_account_id = :party
				""").param("split", split).param("percent", percent).param("party", party).update());
		this.jdbc.sql("UPDATE job.milestone_split SET status = 'FINAL', final_version_no = 1 WHERE id = :split").param("split", split).update();
	}

	private MilestoneSplitFinalized event(UUID split, Map<UUID, Integer> percentages) {
		return new MilestoneSplitFinalized(UUID.randomUUID(), split, this.world.job(), this.world.product(), this.milestone, 1,
				"Sửa lỗi đặt bàn", this.world.hung(), 1, percentages, Finality.CONFIRMED);
	}

	private void publish(MilestoneSplitFinalized event) {
		this.transactions.executeWithoutResult(status -> this.events.publishEvent(event));
	}

	private void refusedOnce(String reason) throws Exception {
		awaitListeners();
		assertThat(this.jdbc.sql("SELECT payload ->> 'reason' FROM operations.external_operation WHERE type = 'payment.split_pool_refused'")
			.query(String.class).list()).containsExactly(reason);
		assertThat(count("SELECT count(*) FROM payment.ledger_entry WHERE entry_type IN ('RELEASE', 'POOL_RELEASED')")).isZero();
		this.worker.runDue(Lane.VNPAY);
		assertThat(this.failed.findAll()).extracting(FailedOperations.FailedOperation::type, FailedOperations.FailedOperation::errorCode)
			.contains(org.assertj.core.groups.Tuple.tuple(SplitPoolRefusedOperation.TYPE, "SPLIT_POOL_REFUSED"));
	}

	/** Lets the listeners of what was published finish: the registry holds a publication until its listener did. */
	private void awaitListeners() {
		await().atMost(Duration.ofSeconds(15)).until(() -> count("""
				SELECT count(*) FROM platform.event_publication WHERE completion_date IS NULL
				""") == 0);
	}

	private List<SplitParty> parties() {
		return this.splits.splitPartiesOf(this.milestone);
	}

	private List<UUID> accounts() {
		return parties().stream().map(SplitParty::accountId).toList();
	}

	private List<Integer> positions() {
		return this.jdbc.sql("SELECT position FROM job.milestone_split_party ORDER BY position").query(Integer.class).list();
	}

	private List<String> ledgerRows() {
		return this.jdbc.sql("""
				SELECT entry_type || ':' || sum(amount_vnd) FROM payment.ledger_entry WHERE milestone_id = :id
				GROUP BY entry_type ORDER BY entry_type
				""").param("id", this.milestone).query(String.class).list();
	}

	private long partOf(UUID party) {
		return this.jdbc.sql("SELECT amount_vnd FROM payment.ledger_entry WHERE entry_type = 'RELEASE' AND party_account_id = :party")
			.param("party", party)
			.query(Long.class)
			.single();
	}

	private long notices(UUID account) {
		return this.jdbc.sql("SELECT count(*) FROM notification.notification WHERE type = 'payment.funds_released' AND account_id = :id")
			.param("id", account)
			.query(Long.class)
			.single();
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
