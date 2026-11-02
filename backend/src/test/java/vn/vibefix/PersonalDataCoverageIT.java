package vn.vibefix;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.session.Session;
import org.springframework.session.SessionRepository;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.support.TransactionTemplate;

import vn.vibefix.authz.JobContent;
import vn.vibefix.identity.internal.IdentityTestSupport;
import vn.vibefix.identity.internal.Role;
import vn.vibefix.job.JobRole;
import vn.vibefix.job.JobRoles;
import vn.vibefix.job.MilestoneSplitFinalized;
import vn.vibefix.job.MilestoneSplitFinalized.Finality;
import vn.vibefix.job.MilestoneSplits;
import vn.vibefix.shared.ErasureBlocker;
import vn.vibefix.shared.ErasureOutcome;
import vn.vibefix.shared.PersonalData;
import vn.vibefix.shared.PersonalDataParticipant;
import vn.vibefix.shared.PersonalDataSection;
import vn.vibefix.support.HeldCrewJob;
import vn.vibefix.support.IntegrationTest;
import vn.vibefix.support.PostgresTestDatabase;

/**
 * AD-24, SG-37: every table that can hold personal data has a {@link PersonalDataParticipant}. A
 * table qualifies when a column names a person ({@link #PERSONAL_COLUMN}: an account ID under any of
 * the role names, a GitHub user ID, an email address, a session principal), or when it is one of the
 * side stores whose payloads name accounts: the event publication registry, the session tables, and
 * every table of {@code operations}. {@code audit} is exempt, because it holds internal IDs only.
 * <p>
 * It also proves what is kept (AD-24, A-01, Story 22.1): the split tables and the ledger are only ever anonymized, and a
 * Product deletion, an erasure or the retention run with an open split leaves its evidence in {@code chat}, {@code work}
 * and {@code delivery}, each with its deferral, and a bare Job row nobody sees, until the Job's last split is final.
 */
class PersonalDataCoverageIT extends IntegrationTest {

	static final Set<String> EXEMPT_SCHEMAS = Set.of("audit");

	static final Set<String> SIDE_STORES = Set.of("platform.event_publication", "platform.event_publication_archive",
			"platform.spring_session", "platform.spring_session_attributes");

	static final String SIDE_STORE_SCHEMA = "operations";

	/** Column names that identify a person. */
	static final Pattern PERSONAL_COLUMN = Pattern.compile(
			"(?:.*_)?(?:account|customer|mentor|student|admin|administrator|author|raiser|subject|user|github_user)_id"
					+ "|(?:.*_)?email|principal_name");

	@Autowired
	private ApplicationContext context;

	@Autowired
	private PersonalData personalData;

	@Autowired
	private JdbcClient jdbc;

	@Autowired
	private TransactionTemplate transactions;

	@Autowired
	private ApplicationEventPublisher events;

	@Autowired
	private MilestoneSplits splits;

	@Autowired
	private JobRoles roles;

	@Autowired
	private MockMvc mvc;

	@Autowired
	private SessionRepository<? extends Session> sessions;

	@Test
	void everyTableWithPersonalDataHasAParticipant() throws Exception {
		Set<String> tables = tablesHoldingPersonalData();
		Set<String> covered = new HashSet<>();
		this.context.getBeansOfType(PersonalDataParticipant.class).values().forEach(p -> covered.addAll(p.tables()));

		assertThat(tables).as("the event publication registry is checked").contains("platform.event_publication");
		assertThat(violations(tables, covered, allTables())).isEmpty();
	}

	@Test
	void theCheckFindsATableWithoutAParticipantAndAParticipantOfNoTable() {
		assertThat(violations(Set.of("identity.account", "platform.event_publication"),
				Set.of("platform.event_publication", "team.gone"),
				Set.of("identity.account", "platform.event_publication")))
			.containsExactly("identity.account holds account IDs but no PersonalDataParticipant covers it",
					"a PersonalDataParticipant covers team.gone, which does not exist");
	}

	@Test
	void personalColumnsAreRecognizedByName() {
		for (String column : List.of("account_id", "student_account_id", "member_account_id", "mentor_id", "customer_id",
				"raiser_id", "author_id", "github_user_id", "assignee_github_user_id", "email", "school_email",
				"principal_name")) {
			assertThat(PERSONAL_COLUMN.matcher(column).matches()).as(column).isTrue();
		}
		for (String column : List.of("id", "job_id", "product_id", "listener_id", "event_id", "publication_id",
				"emails_sent", "correlation_id")) {
			assertThat(PERSONAL_COLUMN.matcher(column).matches()).as(column).isFalse();
		}
	}

	@Test
	void theSplitTablesAreCoveredAndTheRuntimeRoleCannotDeleteFromThem() throws Exception {
		Set<String> covered = new HashSet<>();
		this.context.getBeansOfType(PersonalDataParticipant.class).values().forEach(p -> covered.addAll(p.tables()));
		for (String table : List.of("job.milestone_split", "job.milestone_split_party", "job.milestone_split_version",
				"job.milestone_split_share")) {
			assertThat(covered).contains(table);
			assertThat(query("SELECT has_table_privilege('%s', '%s', 'DELETE')".formatted(PostgresTestDatabase.APP, table)))
				.as("%s is never deleted", table)
				.containsExactly("f");
		}
	}

	@Test
	void aProductDeletionWithAnOpenSplitKeepsItsEvidenceAndItsMoneyAndFinishesWhenTheSplitIsFinal() throws Exception {
		HeldCrewJob world = closedJob();
		UUID split = world.split(this.jdbc, this.clock.instant(), world.milestone(), world.vy());
		Evidence evidence = evidence(world);
		long ledger = count("SELECT count(*) FROM payment.ledger_entry");
		String order = text("SELECT id::text FROM payment.payment_order");

		this.transactions.executeWithoutResult(status -> this.personalData.deleteProduct(world.product()));

		// She sees the Product deleted at once, and nobody else sees the Job.
		assertThat(this.roles.roleOf(world.job(), world.lan())).isEqualTo(JobRole.NONE);
		assertThat(this.roles.roleOf(world.job(), world.hung())).isEqualTo(JobRole.NONE);
		this.mvc.perform(get("/api/v1/jobs/" + world.job()).with(as(world.lan(), Role.CUSTOMER))).andExpect(status().isNotFound());
		this.mvc.perform(get("/api/v1/jobs/" + world.job() + "/funding").with(as(world.lan(), Role.CUSTOMER)))
			.andExpect(status().isNotFound());
		this.mvc.perform(get("/api/v1/payment-orders/" + order).with(as(world.lan(), Role.CUSTOMER))).andExpect(status().isNotFound());
		this.mvc.perform(get("/api/v1/payments/history").with(as(world.lan(), Role.CUSTOMER)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.jobs").isEmpty())
			.andExpect(jsonPath("$.releasedVnd").value(0));
		assertThat(count("SELECT count(*) FROM notification.notification WHERE account_id = '" + world.lan() + "'")).isZero();

		// A bare row, the split, and the money stay.
		assertThat(text("SELECT title || ':' || (product_deleted_at IS NOT NULL) || ':' || (problem IS NULL) || ':' || (expected IS NULL)"
				+ " FROM job.job")).isEqualTo("Sửa lỗi đặt bàn:true:true:true");
		assertThat(count("SELECT count(*) FROM job.milestone")).isZero();
		assertThat(count("SELECT count(*) FROM job.crew_row")).isZero();
		assertThat(count("SELECT count(*) FROM job.milestone_split_party WHERE split_id = '" + split + "'")).isEqualTo(2);
		assertThat(count("SELECT count(*) FROM payment.ledger_entry")).isEqualTo(ledger);
		assertThat(count("SELECT count(*) FROM payment.ledger_account")).isOne();
		assertThat(count("SELECT count(*) FROM payment.payment_order")).isOne();

		// The evidence stays, each module's deferral says what waits, and the rest of what they hold has gone.
		assertThat(this.splits.hasOpenSplit(world.job())).isTrue();
		assertDeferred(world, "DELETE");
		assertThat(this.jdbc.sql("SELECT kind FROM chat.chat").query(String.class).list()).containsExactly("CREW");
		assertThat(count("SELECT count(*) FROM chat.message")).isOne();
		assertThat(count("SELECT count(*) FROM work.work_item WHERE id = '" + evidence.item() + "'")).isOne();
		assertThat(count("SELECT count(*) FROM work.work_item_history")).isOne();
		assertThat(count("SELECT count(*) FROM work.page")).isZero();
		assertThat(count("SELECT count(*) FROM delivery.handover")).isOne();
		assertThat(count("SELECT author_github_id FROM delivery.handover_commit")).isEqualTo(4242);
		assertThat(count("SELECT count(*) FROM delivery.handover_criterion")).isZero();

		// The split becomes final: the pool is paid out, and what waited goes, the bare row last.
		finish(world, split, Map.of(world.hung(), 20, world.vy(), 80));

		assertThat(count("SELECT count(*) FROM job.job")).isZero();
		assertNoDeferrals();
		assertThat(count("SELECT count(*) FROM chat.chat")).isZero();
		assertThat(count("SELECT count(*) FROM chat.message")).isZero();
		assertThat(count("SELECT count(*) FROM work.work_item")).isZero();
		assertThat(count("SELECT count(*) FROM work.board")).isZero();
		assertThat(count("SELECT count(*) FROM delivery.handover")).isZero();
		assertThat(count("SELECT count(*) FROM delivery.handover_commit")).isZero();
		assertThat(count("SELECT count(*) FROM job.milestone_split_party WHERE split_id = '" + split + "'")).isEqualTo(2);
		assertThat(count("SELECT count(*) FROM job.milestone_split_version")).isOne();
		assertThat(this.jdbc.sql("SELECT amount_vnd FROM payment.ledger_entry WHERE entry_type = 'RELEASE' ORDER BY amount_vnd")
			.query(Long.class).list()).containsExactly(HeldCrewJob.PRICE * 20 / 100, HeldCrewJob.PRICE * 80 / 100);
		this.mvc.perform(get("/api/v1/balance").with(as(world.vy(), Role.STUDENT)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.balanceVnd").value(HeldCrewJob.PRICE * 80 / 100));
	}

	@Test
	void aProductDeletionWithNoOpenSplitDeletesAtOnceButKeepsTheMoneyAndTheFinalSplit() {
		HeldCrewJob world = closedJob();
		UUID split = world.split(this.jdbc, this.clock.instant(), world.milestone(), world.vy());
		evidence(world);
		finalizeRows(split, Map.of(world.hung(), 20, world.vy(), 80));
		long ledger = count("SELECT count(*) FROM payment.ledger_entry");

		this.transactions.executeWithoutResult(status -> this.personalData.deleteProduct(world.product()));

		assertThat(count("SELECT count(*) FROM job.job")).isZero();
		assertNoDeferrals();
		assertThat(count("SELECT count(*) FROM chat.chat")).isZero();
		assertThat(count("SELECT count(*) FROM work.board")).isZero();
		assertThat(count("SELECT count(*) FROM delivery.handover")).isZero();
		assertThat(count("SELECT count(*) FROM job.milestone_split")).isOne();
		assertThat(count("SELECT count(*) FROM payment.ledger_entry")).isEqualTo(ledger);
	}

	@Test
	void theRetentionRunWithAnOpenSplitKeepsItsEvidenceAndFinishesWhenTheSplitIsFinal() {
		HeldCrewJob world = closedJob();
		UUID split = world.split(this.jdbc, this.clock.instant(), world.milestone(), world.vy());
		evidence(world);

		this.transactions.executeWithoutResult(status -> this.personalData.retireJobData(world.product()));

		assertThat(text("SELECT state || ':' || (product_deleted_at IS NULL) FROM job.job")).isEqualTo("CLOSED:true");
		assertDeferred(world, "RETIRE");
		assertThat(this.jdbc.sql("SELECT kind FROM chat.chat").query(String.class).list()).containsExactly("CREW");
		assertThat(count("SELECT count(*) FROM work.work_item")).isOne();
		assertThat(count("SELECT count(*) FROM work.page")).isZero();
		assertThat(count("SELECT count(*) FROM delivery.handover_commit")).isOne();
		assertThat(count("SELECT count(*) FROM delivery.handover_criterion")).isZero();

		finish(world, split, Map.of(world.hung(), 20, world.vy(), 80));

		assertNoDeferrals();
		assertThat(count("SELECT count(*) FROM chat.chat")).isZero();
		assertThat(count("SELECT count(*) FROM work.work_item")).isZero();
		assertThat(count("SELECT count(*) FROM work.board")).as("a retirement leaves each Board, empty").isOne();
		assertThat(count("SELECT count(*) FROM delivery.handover_commit")).isZero();
		assertThat(count("SELECT count(*) FROM delivery.handover")).as("a retirement leaves each handover row").isOne();
		assertThat(count("SELECT count(*) FROM job.job")).as("the Product stays, and so does its Job").isOne();
	}

	@Test
	void aDeletionAfterARetirementOutranksItWhileTheSplitIsOpen() {
		HeldCrewJob world = closedJob();
		UUID split = world.split(this.jdbc, this.clock.instant(), world.milestone(), world.vy());
		evidence(world);

		this.transactions.executeWithoutResult(status -> this.personalData.retireJobData(world.product()));
		this.transactions.executeWithoutResult(status -> this.personalData.deleteProduct(world.product()));
		assertDeferred(world, "DELETE");

		finish(world, split, Map.of(world.hung(), 20, world.vy(), 80));
		assertThat(count("SELECT count(*) FROM work.board")).isZero();
		assertThat(count("SELECT count(*) FROM delivery.handover")).isZero();
	}

	@Test
	void aLaterSplitOfTheJobKeepsTheEvidenceUntilItToIsFinal() {
		HeldCrewJob world = closedJob();
		UUID first = world.split(this.jdbc, this.clock.instant(), world.milestone(), world.vy());
		UUID secondMilestone = UUID.randomUUID();
		UUID second = world.split(this.jdbc, this.clock.instant(), secondMilestone, world.vy());
		evidence(world);
		this.transactions.executeWithoutResult(status -> this.personalData.deleteProduct(world.product()));

		finalizeRows(first, Map.of(world.hung(), 20, world.vy(), 80));
		publish(world, first, world.milestone(), Map.of(world.hung(), 20, world.vy(), 80));
		awaitListeners();
		assertDeferred(world, "DELETE");
		assertThat(count("SELECT count(*) FROM job.job")).as("the second split is open").isOne();
		assertThat(count("SELECT count(*) FROM chat.chat")).isOne();

		finalizeRows(second, Map.of(world.hung(), 20, world.vy(), 80));
		publish(world, second, secondMilestone, Map.of(world.hung(), 20, world.vy(), 80));
		awaitListeners();
		assertNoDeferrals();
		assertThat(count("SELECT count(*) FROM job.job")).isZero();
		assertThat(count("SELECT count(*) FROM chat.chat")).isZero();
	}

	/** Under way, a finalization holds the Job row: the read waits for it and then sees it final (AD-4). */
	@Test
	void aFinalizationUnderWayIsWaitedForAndSeenSoItCannotSlipPastADeferral() throws Exception {
		HeldCrewJob world = closedJob();
		UUID split = world.split(this.jdbc, this.clock.instant(), world.milestone(), world.vy());
		AtomicReference<CompletableFuture<Set<UUID>>> read = new AtomicReference<>();

		this.transactions.executeWithoutResult(status -> {
			this.jdbc.sql("SELECT id FROM job.job WHERE id = :id FOR UPDATE").param("id", world.job()).query(UUID.class).single();
			finalizeRows(split, Map.of(world.hung(), 20, world.vy(), 80));
			read.set(CompletableFuture.supplyAsync(() -> this.splits.openSplitJobs(world.product())));
			await().during(Duration.ofMillis(500)).atMost(Duration.ofSeconds(5)).until(() -> !read.get().isDone());
		});

		assertThat(read.get().get(15, TimeUnit.SECONDS)).isEmpty();
	}

	@Test
	void anAccountWaitsOnAnOpenSplitItIsAPartyOfAndOnceAJob() {
		HeldCrewJob world = closedJob();
		world.split(this.jdbc, this.clock.instant(), world.milestone(), world.vy());
		world.split(this.jdbc, this.clock.instant(), UUID.randomUUID(), world.vy());

		assertThat(this.personalData.erasureBlockers(world.vy())).extracting(ErasureBlocker::reason, ErasureBlocker::jobId)
			.containsExactly(org.assertj.core.groups.Tuple.tuple("SPLIT_PENDING", world.job()));
		assertThat(this.personalData.erasureBlockers(world.hung())).extracting(ErasureBlocker::reason).containsExactly("SPLIT_PENDING");
		assertThat(this.personalData.erasureBlockers(world.lan())).as("the Customer is no party").isEmpty();
		assertThat(erase(world.vy())).isInstanceOf(ErasureOutcome.Blocked.class);
	}

	@Test
	void aMilestoneFundedExactlyWhenAMemberLeftWaitsForThem() {
		HeldCrewJob world = closedJob();
		this.jdbc.sql("UPDATE job.milestone SET state = 'FUNDED', accepted_at = NULL").update();
		this.jdbc.sql("UPDATE job.crew_row SET window_end = (SELECT funded_at FROM job.milestone) WHERE member_account_id = :vy")
			.param("vy", world.vy())
			.update();

		assertThat(this.personalData.erasureBlockers(world.vy())).extracting(ErasureBlocker::reason).containsExactly("MILESTONE_PENDING");
	}

	@Test
	void anErasureOfAPartyOfAFinalSplitAnonymizesThePersonAndKeepsEveryRow() {
		HeldCrewJob world = closedJob();
		UUID split = world.split(this.jdbc, this.clock.instant(), world.milestone(), world.vy());
		finish(world, split, Map.of(world.hung(), 20, world.vy(), 80));
		long ledger = count("SELECT count(*) FROM payment.ledger_entry");

		PersonalDataSection section = this.transactions.execute(status -> this.personalData.export(world.vy()))
			.stream()
			.filter(found -> "job".equals(found.module()))
			.findFirst()
			.orElseThrow();
		assertThat(section.toString()).contains("milestoneSplitParts").contains("percent=80").doesNotContain("percent=20");

		assertThat(erase(world.vy())).isInstanceOf(ErasureOutcome.Erased.class);
		assertThat(erase(world.hung())).isInstanceOf(ErasureOutcome.Erased.class);

		assertThat(count("SELECT count(*) FROM job.milestone_split_party WHERE split_id = '" + split + "'")).isEqualTo(2);
		assertThat(count("SELECT count(*) FROM job.milestone_split_party WHERE party_account_id IS NOT NULL")).isZero();
		assertThat(count("SELECT count(*) FROM job.milestone_split WHERE mentor_account_id IS NOT NULL")).isZero();
		assertThat(count("SELECT count(*) FROM job.milestone_split_version WHERE author_account_id IS NOT NULL")).isZero();
		assertThat(count("SELECT count(*) FROM job.milestone_split_share")).isEqualTo(2);
		assertThat(count("SELECT count(*) FROM payment.ledger_entry")).as("the RELEASE entries stay").isEqualTo(ledger);
		assertThat(count("SELECT count(*) FROM payment.ledger_entry WHERE entry_type = 'RELEASE'")).isEqualTo(2);
	}

	@Test
	void anErasureOfACustomerKeepsTheMoneyWithoutThemAndAnOpenSplitsJobWithNobody() {
		HeldCrewJob world = closedJob();
		UUID split = world.split(this.jdbc, this.clock.instant(), world.milestone(), world.vy());
		evidence(world);
		long ledger = count("SELECT count(*) FROM payment.ledger_entry");

		assertThat(erase(world.lan())).isInstanceOf(ErasureOutcome.Erased.class);

		assertThat(text("SELECT customer_account_id::text || ':' || (product_deleted_at IS NOT NULL) FROM job.job"))
			.isEqualTo(new UUID(0, 0) + ":true");
		assertThat(count("SELECT count(*) FROM payment.payment_order WHERE customer_account_id IS NULL")).isOne();
		assertThat(count("SELECT count(*) FROM payment.ledger_entry")).isEqualTo(ledger);
		assertDeferred(world, "DELETE");

		finish(world, split, Map.of(world.hung(), 20, world.vy(), 80));
		assertThat(count("SELECT count(*) FROM job.job")).isZero();
		assertNoDeferrals();
		assertThat(count("SELECT count(*) FROM payment.ledger_entry WHERE entry_type = 'RELEASE'")).isEqualTo(2);
	}

	/** What a split's evidence hangs from. */
	private record Evidence(UUID item, UUID handover) {
	}

	/** The world's Job closed with its Milestone accepted and its Crew released, its pool holding the whole price. */
	private HeldCrewJob closedJob() {
		Instant now = this.clock.instant();
		HeldCrewJob world = HeldCrewJob.create(this.jdbc, now);
		this.jdbc.sql("UPDATE job.job SET state = 'CLOSED', closed_at = :now").param("now", Timestamp.from(now)).update();
		this.jdbc.sql("UPDATE job.milestone SET state = 'ACCEPTED', accepted_at = :now").param("now", Timestamp.from(now)).update();
		this.jdbc.sql("""
				UPDATE job.crew_row SET status = 'RELEASED', released_reason = 'JOB_ENDED', released_at = :now, window_end = :now
				""").param("now", Timestamp.from(now)).update();
		JobContent.pool(this.jdbc, world.job(), world.product(), world.milestone(), HeldCrewJob.PRICE, now);
		return world;
	}

	/** One of each thing a split's evidence, and what does not wait, is made of. */
	private Evidence evidence(HeldCrewJob world) {
		Instant at = this.clock.instant();
		UUID jobChat = JobContent.chat(this.jdbc, world.job(), world.product(), "JOB");
		JobContent.message(this.jdbc, jobChat, world.lan(), "Chào", at);
		UUID crewChat = JobContent.chat(this.jdbc, world.job(), world.product(), "CREW");
		JobContent.message(this.jdbc, crewChat, world.vy(), "Phần việc của mình", at);
		JobContent.board(this.jdbc, world.job(), world.product(), "SDB");
		UUID item = JobContent.workItem(this.jdbc, world.job(), null, null, at);
		JobContent.note(this.jdbc, item, world.job(), world.vy(), "Đã xong", at);
		JobContent.page(this.jdbc, world.job(), world.vy(), "Trang", at);
		UUID handover = UUID.randomUUID();
		this.jdbc.sql("""
				INSERT INTO delivery.handover (id, job_id, product_id, milestone_id, milestone_number, milestone_title, round,
				    mentor_account_id, status, job_version, transition_id, check_by, created_at)
				VALUES (:id, :job, :product, :milestone, 1, 'Giai đoạn 1', 1, :mentor, 'SUBMITTED', 5, :transition, :at, :at)
				""")
			.param("id", handover)
			.param("job", world.job())
			.param("product", world.product())
			.param("milestone", world.milestone())
			.param("mentor", world.hung())
			.param("transition", UUID.randomUUID())
			.param("at", Timestamp.from(at))
			.update();
		this.jdbc.sql("""
				INSERT INTO delivery.handover_commit (handover_id, position, sha, message, author_github_id)
				VALUES (:id, 0, :sha, 'Sửa lỗi', 4242)
				""").param("id", handover).param("sha", "a".repeat(40)).update();
		this.jdbc.sql("""
				INSERT INTO delivery.handover_criterion (handover_id, criterion_id, number, statement, check_steps, met, note)
				VALUES (:id, :criterion, 1, 'Tiêu chí 1', 'Cách kiểm tra 1', true, 'Đã kiểm tra')
				""").param("id", handover).param("criterion", UUID.randomUUID()).update();
		return new Evidence(item, handover);
	}

	private void assertDeferred(HeldCrewJob world, String waiting) {
		for (String table : List.of("chat.split_deferral", "work.split_deferral", "delivery.split_deferral")) {
			assertThat(text("SELECT job_id || ':' || product_id || ':' || waiting FROM " + table))
				.as(table)
				.isEqualTo(world.job() + ":" + world.product() + ":" + waiting);
		}
	}

	private void assertNoDeferrals() {
		for (String table : List.of("chat.split_deferral", "work.split_deferral", "delivery.split_deferral")) {
			assertThat(count("SELECT count(*) FROM " + table)).as(table).isZero();
		}
	}

	/** The split becomes final as Story 22.2 will make it, and every listener has run. */
	private void finish(HeldCrewJob world, UUID split, Map<UUID, Integer> percentages) {
		finalizeRows(split, percentages);
		publish(world, split, world.milestone(), percentages);
		awaitListeners();
	}

	private void finalizeRows(UUID split, Map<UUID, Integer> percentages) {
		this.jdbc.sql("""
				INSERT INTO job.milestone_split_version (split_id, version_no, kind, cap_percent, author_account_id, created_at)
				SELECT :split, 1, 'DRAFT', 30, mentor_account_id, :now FROM job.milestone_split WHERE id = :split
				""").param("split", split).param("now", Timestamp.from(this.clock.instant())).update();
		percentages.forEach((party, percent) -> this.jdbc.sql("""
				INSERT INTO job.milestone_split_share (split_id, version_no, party_id, percent)
				SELECT :split, 1, id, :percent FROM job.milestone_split_party WHERE split_id = :split AND party_account_id = :party
				""").param("split", split).param("percent", percent).param("party", party).update());
		this.jdbc.sql("UPDATE job.milestone_split SET status = 'FINAL', final_version_no = 1 WHERE id = :split")
			.param("split", split)
			.update();
	}

	private void publish(HeldCrewJob world, UUID split, UUID milestone, Map<UUID, Integer> percentages) {
		this.transactions.executeWithoutResult(status -> this.events.publishEvent(new MilestoneSplitFinalized(UUID.randomUUID(), split,
				world.job(), world.product(), milestone, 1, "Sửa lỗi đặt bàn", world.hung(), 1, new LinkedHashMap<>(percentages),
				Finality.CONFIRMED)));
	}

	private void awaitListeners() {
		await().atMost(Duration.ofSeconds(20))
			.until(() -> count("SELECT count(*) FROM platform.event_publication WHERE completion_date IS NULL") == 0);
	}

	private ErasureOutcome erase(UUID account) {
		return this.transactions.execute(status -> this.personalData.erase(account));
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

	static List<String> violations(Set<String> tablesWithAccountIds, Set<String> covered, Set<String> existing) {
		List<String> violations = new ArrayList<>();
		new TreeSet<>(tablesWithAccountIds).stream()
			.filter(table -> !covered.contains(table))
			.forEach(table -> violations.add(table + " holds account IDs but no PersonalDataParticipant covers it"));
		new TreeSet<>(covered).stream()
			.filter(table -> !existing.contains(table))
			.forEach(table -> violations.add("a PersonalDataParticipant covers " + table + ", which does not exist"));
		return violations;
	}

	private static Set<String> tablesHoldingPersonalData() throws Exception {
		Set<String> tables = new TreeSet<>();
		for (String row : query("""
				SELECT table_schema || '.' || table_name || ' ' || column_name FROM information_schema.columns
				WHERE table_schema NOT IN ('pg_catalog', 'information_schema')
				""")) {
			String[] tableAndColumn = row.split(" ", 2);
			if (PERSONAL_COLUMN.matcher(tableAndColumn[1]).matches()) {
				tables.add(tableAndColumn[0]);
			}
		}
		for (String table : allTables()) {
			if (SIDE_STORES.contains(table) || table.startsWith(SIDE_STORE_SCHEMA + ".")) {
				tables.add(table);
			}
		}
		tables.removeIf(table -> EXEMPT_SCHEMAS.contains(table.substring(0, table.indexOf('.'))));
		return tables;
	}

	private static Set<String> allTables() throws Exception {
		return new TreeSet<>(query("""
				SELECT table_schema || '.' || table_name FROM information_schema.tables
				WHERE table_schema NOT IN ('pg_catalog', 'information_schema') AND table_type = 'BASE TABLE'
				"""));
	}

	private static List<String> query(String sql) throws Exception {
		List<String> values = new ArrayList<>();
		try (Connection connection = database.superuserConnection(PostgresTestDatabase.DATABASE);
				Statement statement = connection.createStatement();
				ResultSet rows = statement.executeQuery(sql)) {
			while (rows.next()) {
				values.add(rows.getString(1));
			}
		}
		return values;
	}

}
