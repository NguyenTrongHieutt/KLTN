package vn.vibefix.repo.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;

import vn.vibefix.dispute.internal.DisputeTestSupport;
import vn.vibefix.github.internal.GitHubStub;
import vn.vibefix.github.internal.TestKeys;
import vn.vibefix.identity.internal.IdentityTestSupport;
import vn.vibefix.identity.internal.Role;
import vn.vibefix.job.ClosureProposalChanged;
import vn.vibefix.job.JobFreezes;
import vn.vibefix.job.JobTransitioned;
import vn.vibefix.support.IntegrationTest;
import vn.vibefix.timers.Deadlines;
import vn.vibefix.timers.internal.DeadlinePoller;

/**
 * Story 23.9a (FR-37, FR-67, R1, D7) and Story 23.10a (FR-38, D10, AD-7): a Code Job's closing-choice row opens at
 * the first closure proposal, gets its FR-37 deadline at J8 with the to-do {@code repo.receive_code} and the 2-day
 * reminder {@code repo.receive_code_reminder}, and ends when its proposal ends without J8 or when the deadline
 * passes with neither way of receiving the code chosen, which hands the Job to the closure fallback (Story 8.9,
 * which Story 23.10b re-points). There is no app to install at the end, so no install link, no organization request
 * and no {@code BOUND} state are left; the deadline and the reminder are both pausable, so a Dispute that wins the
 * race ends nothing. GitHub is {@link GitHubStub}.
 */
class FinalMergeAuthorizationIT extends IntegrationTest {

	private static final Path DIRECTORY;

	private static final GitHubStub GITHUB;

	private static final AtomicLong IDS = new AtomicLong(880000);

	static {
		try {
			DIRECTORY = Files.createTempDirectory("final-merge-");
			GITHUB = new GitHubStub(Files.createDirectories(DIRECTORY.resolve("git")));
			Files.writeString(DIRECTORY.resolve("app-key"), TestKeys.pkcs1());
		}
		catch (Exception ex) {
			throw new IllegalStateException(ex);
		}
	}

	@Autowired
	private JdbcClient jdbc;

	@Autowired
	private TransactionTemplate transactions;

	@Autowired
	private ApplicationEventPublisher events;

	@Autowired
	private FinalMergeAuthorizations authorizations;

	@Autowired
	private DeadlinePoller poller;

	@Autowired
	private Deadlines deadlines;

	@Autowired
	private JobFreezes freezes;

	@Autowired
	private ApplicationContext context;

	private UUID lan;

	private UUID job;

	@DynamicPropertySource
	static void github(DynamicPropertyRegistry registry) {
		registry.add("vibefix.github.api-url", () -> GITHUB.apiUrl().toString());
		registry.add("vibefix.github.git-url", () -> GITHUB.gitUrl().toString());
	}

	@AfterAll
	static void stop() {
		GITHUB.close();
	}

	@BeforeEach
	void anActiveCodeJob() {
		this.lan = IdentityTestSupport.insertAccount(this.jdbc, Role.CUSTOMER, null);
		UUID mentor = IdentityTestSupport.insertAccount(this.jdbc, Role.MENTOR, null);
		long repository = IDS.incrementAndGet();
		String owner = "quan-may-" + IDS.incrementAndGet();
		UUID product = UUID.randomUUID();
		this.jdbc.sql("""
				INSERT INTO product.product (id, customer_account_id, name, description, ai_tools, repository_github_id,
				    repository_full_name, repository_owner_type, repository_default_branch, public_notice_version,
				    copy_permission_version, detection_notice_version, acknowledged_at, created_at)
				VALUES (:id, :owner, 'Sổ đặt bàn Quán Mây', 'Đặt bàn.', ARRAY['LOVABLE'], :github, :name, 'Organization', 'main',
				    '1.0', '1.0', '1.0', now(), now())
				""").param("id", product).param("owner", this.lan).param("github", repository)
			.param("name", owner + "/so-dat-ban").update();
		this.job = UUID.randomUUID();
		Timestamp now = Timestamp.from(this.clock.instant());
		this.jdbc.sql("""
				INSERT INTO job.job (id, product_id, customer_account_id, state, title, problem, expected, app_users,
				    job_types, budget, deadline, rules, selection_days, opened_at, lapses_at, mentor_account_id,
				    selected_application_id, selected_at, version, created_at, updated_at)
				VALUES (:id, :product, :owner, 'ACTIVE', 'Sửa lỗi đặt bàn', 'Lỗi', 'Đúng', 'Khách', ARRAY['BUG_FIX'],
				    'UNDER_2M', DATE '2026-12-01', '{"FINAL_MERGE_AUTHORIZATION_DAYS":5}'::jsonb, 14, :now, :now, :mentor,
				    :application, :now, 5, :now, :now)
				""").param("id", this.job).param("product", product).param("owner", this.lan).param("mentor", mentor)
			.param("application", UUID.randomUUID()).param("now", now).update();
	}

	@Test
	void theFirstProposalOpensItAndJ8SetsItsDeadlineAndItsOnlyTimer() {
		publish(new ClosureProposalChanged(UUID.randomUUID(), this.job, UUID.randomUUID(), ClosureProposalChanged.Status.OPEN,
				true));
		await().atMost(Duration.ofSeconds(15)).until(() -> authorizationStatus().equals("REQUESTED"));
		publish(new ClosureProposalChanged(UUID.randomUUID(), this.job, UUID.randomUUID(), ClosureProposalChanged.Status.OPEN,
				false));
		assertThat(openTodos()).isOne();

		this.jdbc.sql("UPDATE job.job SET state = 'CLOSING' WHERE id = :id").param("id", this.job).update();
		publish(new JobTransitioned(UUID.randomUUID(), UUID.randomUUID(), this.job, "J8", "USER", "ACTIVE", "CLOSING", 6, 1,
				Map.of()));
		await().atMost(Duration.ofSeconds(15)).until(() -> this.jdbc
			.sql("SELECT deadline_at IS NOT NULL FROM repo.final_merge_authorization WHERE job_id = :job")
			.param("job", this.job).query(Boolean.class).single());

		assertThat(this.jdbc.sql("SELECT count(*) FROM repo.final_merge_authorization WHERE job_id = :job")
			.param("job", this.job).query(Long.class).single()).as("one per Job").isOne();
		assertThat(this.jdbc.sql("SELECT deadline_at FROM repo.final_merge_authorization WHERE job_id = :job")
			.param("job", this.job).query(Timestamp.class).single().toInstant())
			.as("the Job's copied 5 days").isEqualTo(this.clock.instant().plus(Duration.ofDays(5)));
		assertThat(this.jdbc.sql("""
				SELECT kind FROM timers.deadline WHERE job_id = :job AND kind LIKE 'repo.%' ORDER BY due_at
				""").param("job", this.job).query(String.class).list())
			.as("the FR-37 deadline and the 2-day reminder before it (AD-7, Story 23.10a)")
			.containsExactly("repo.receive_code_reminder", "repo.final_merge_deadline");
		assertThat(deadlineRow("repo.receive_code_reminder").get("due_at"))
			.as("two days before the deadline")
			.isEqualTo(Timestamp.from(this.clock.instant().plus(Duration.ofDays(3))));
		assertThat(notifications("repo.final_merge_authorization_pending")).isZero();
		assertThat(this.jdbc.sql("""
				SELECT deadline_at IS NOT NULL FROM notification.todo WHERE todo_key = 'repo.receive_code'
				    AND account_id = :id AND closed_at IS NULL
				""").param("id", this.lan).query(Boolean.class).single()).as("the to-do carries the deadline").isTrue();
		assertThat(this.jdbc.sql("""
				SELECT params ->> 'job' FROM notification.todo WHERE todo_key = 'repo.receive_code' AND account_id = :id
				    AND closed_at IS NULL
				""").param("id", this.lan).query(String.class).single()).as("and names the Job").isEqualTo("Sửa lỗi đặt bàn");
	}

	/** AD-7: the reminder goes out two days before the deadline, once, while the choice is still open. */
	@Test
	void theReminderGoesOutTwoDaysBeforeTheDeadlineAndOnlyOnce() {
		closingWithItsDeadline();

		this.clock.advance(Duration.ofDays(3).plusMinutes(1));
		assertThat(this.poller.runDue()).isOne();

		assertThat(deadlineRow("repo.receive_code_reminder")).containsEntry("status", "FIRED");
		assertThat(notifications("repo.receive_code_reminder")).isOne();
		assertThat(authorizationStatus()).as("the choice stays open").isEqualTo("REQUESTED");
		assertThat(this.jdbc.sql("""
				SELECT params ->> 'date' FROM notification.notification WHERE type = 'repo.receive_code_reminder'
				""").query(String.class).single()).as("it names the deadline").isNotBlank();
	}

	/** The choice is already made: the Final Merge run is going, so the reminder is obsolete and nothing is sent. */
	@Test
	void theReminderIsObsoleteOnceTheCustomerSetTheFinalMergeGoing() {
		closingWithItsDeadline();
		UUID authorization = this.jdbc
			.sql("SELECT id FROM repo.final_merge_authorization WHERE job_id = :job")
			.param("job", this.job)
			.query(UUID.class)
			.single();
		this.jdbc.sql("""
				INSERT INTO repo.final_merge (authorization_id, job_id, product_id, status, confirmed_at, created_at)
				SELECT :id, job_id, product_id, 'CONFIRMED', now(), now() FROM repo.final_merge_authorization WHERE id = :id
				""").param("id", authorization).update();

		this.clock.advance(Duration.ofDays(3).plusMinutes(1));
		assertThat(this.poller.runDue()).isOne();

		assertThat(deadlineRow("repo.receive_code_reminder")).containsEntry("status", "OBSOLETE");
		assertThat(notifications("repo.receive_code_reminder")).isZero();
	}

	/** Story 23.10a: the Customer has said they already have the code, so the choice is settled and nothing is sent. */
	@Test
	void theReminderIsObsoleteOnceTheCodeReceiptIsRecorded() {
		closingWithItsDeadline();
		UUID setup = UUID.randomUUID();
		this.jdbc.sql("""
				INSERT INTO repo.job_setup (id, job_id, product_id, status, started_at, finished_at)
				SELECT :id, :job, product_id, 'ENDED', now(), now() FROM job.job WHERE id = :job
				""").param("id", setup).param("job", this.job).update();
		this.jdbc.sql("""
				INSERT INTO repo.code_receipt (job_id, setup_id, confirmed_by, confirmed_at)
				VALUES (:job, :setup, :by, now())
				""").param("job", this.job).param("setup", setup).param("by", this.lan).update();

		this.clock.advance(Duration.ofDays(3).plusMinutes(1));
		assertThat(this.poller.runDue()).isOne();

		assertThat(deadlineRow("repo.receive_code_reminder")).containsEntry("status", "OBSOLETE");
		assertThat(notifications("repo.receive_code_reminder")).isZero();
	}

	/** A deadline already inside the reminder's two days gets no reminder at all (AD-7). */
	@Test
	void aDeadlineInsideTwoDaysSchedulesNoReminder() {
		this.jdbc.sql("UPDATE job.job SET rules = '{\"FINAL_MERGE_AUTHORIZATION_DAYS\":1}'::jsonb WHERE id = :id")
			.param("id", this.job)
			.update();
		closingWithItsDeadline();

		assertThat(this.jdbc.sql("SELECT kind FROM timers.deadline WHERE job_id = :job AND kind LIKE 'repo.%'")
			.param("job", this.job)
			.query(String.class)
			.list()).containsExactly("repo.final_merge_deadline");
	}

	@Test
	void aDeclinedProposalEndsItWhileTheJobIsActive() {
		this.authorizations.open(this.job);
		publish(new ClosureProposalChanged(UUID.randomUUID(), this.job, UUID.randomUUID(),
				ClosureProposalChanged.Status.DECLINED, false));
		await().atMost(Duration.ofSeconds(15)).until(() -> authorizationStatus().equals("ENDED"));
		assertThat(this.jdbc.sql("SELECT end_reason FROM repo.final_merge_authorization WHERE job_id = :job")
			.param("job", this.job).query(String.class).single()).isEqualTo("PROPOSAL_ENDED");
		assertThat(openTodos()).isZero();
	}

	@Test
	void theDeadlineEndsAFinalMergeTheCustomerNeverConfirmed() {
		closingWithItsDeadline();
		this.clock.advance(Duration.ofDays(5).plusMinutes(1));
		this.poller.runDue();

		assertThat(authorizationStatus()).isEqualTo("ENDED");
		assertThat(this.jdbc.sql("SELECT end_reason FROM repo.final_merge_authorization WHERE job_id = :job")
			.param("job", this.job).query(String.class).single()).isEqualTo("DEADLINE");
		assertThat(openTodos()).isZero();
		// Story 8.9: the closure fallback starts, saying the Customer chose no way of receiving the code in time.
		await().atMost(Duration.ofSeconds(15)).until(() -> this.jdbc
			.sql("SELECT count(*) FROM repo.zip_fallback WHERE job_id = :job").param("job", this.job).query(Long.class)
			.single() == 1);
		assertThat(this.jdbc.sql("SELECT reason FROM repo.zip_fallback WHERE job_id = :job").param("job", this.job)
			.query(String.class).single()).isEqualTo("NOT_AUTHORIZED");
	}

	/** A-02: {@code end} takes no Job row lock, so the deadline itself must decide under it before ending anything. */
	@Test
	void theDeadlineRacingADisputePausesWithItsTimeEndsNothingAndEndsItAfterTheThaw() throws Exception {
		Instant closing = closingWithItsDeadline();
		// The 2-day reminder comes first, so the race is the deadline's alone.
		this.clock.setInstant(closing.plus(Duration.ofDays(3)).plusSeconds(1));
		assertThat(this.poller.runDue()).isOne();

		this.clock.setInstant(closing.plus(Duration.ofDays(5)));
		DisputeTestSupport.openRacingThePoller(this.context, this.job, this.lan, deadlineId("repo.final_merge_deadline"),
				closing.plus(Duration.ofDays(4)));

		assertThat(deadlineRow("repo.final_merge_deadline")).containsEntry("status", "PAUSED")
			.containsEntry("remaining_ms", Duration.ofDays(1).toMillis());
		assertThat(authorizationStatus()).isEqualTo("REQUESTED");

		thaw();
		this.clock.advance(Duration.ofDays(1));
		assertThat(this.poller.runDue()).isOne();
		assertThat(deadlineRow("repo.final_merge_deadline")).containsEntry("status", "FIRED");
		assertThat(authorizationStatus()).isEqualTo("ENDED");
		await().atMost(Duration.ofSeconds(15)).until(() -> this.jdbc
			.sql("SELECT count(*) FROM repo.zip_fallback WHERE job_id = :job").param("job", this.job).query(Long.class)
			.single() == 1);
	}

	private void publish(Object event) {
		this.transactions.executeWithoutResult(status -> this.events.publishEvent(event));
	}

	/** J8 has set the FR-37 deadline, 5 days from now; @return now */
	private Instant closingWithItsDeadline() {
		this.authorizations.open(this.job);
		this.jdbc.sql("UPDATE job.job SET state = 'CLOSING' WHERE id = :id").param("id", this.job).update();
		this.authorizations.closing(this.job);
		return this.clock.instant();
	}

	private void thaw() {
		this.transactions.executeWithoutResult(status -> {
			this.freezes.unfreeze(this.job);
			this.deadlines.unfreeze(this.job);
		});
	}

	private UUID deadlineId(String kind) {
		return (UUID) deadlineRow(kind).get("id");
	}

	private Map<String, Object> deadlineRow(String kind) {
		return this.jdbc.sql("SELECT * FROM timers.deadline WHERE job_id = :job AND kind = :kind")
			.param("job", this.job).param("kind", kind).query().singleRow();
	}

	private String authorizationStatus() {
		return this.jdbc.sql("""
				SELECT status FROM repo.final_merge_authorization WHERE job_id = :job ORDER BY requested_at DESC LIMIT 1
				""").param("job", this.job).query(String.class).optional().orElse("NONE");
	}

	private long openTodos() {
		return this.jdbc.sql("""
				SELECT count(*) FROM notification.todo WHERE todo_key = 'repo.receive_code' AND account_id = :id
				    AND closed_at IS NULL
				""").param("id", this.lan).query(Long.class).single();
	}

	private long notifications(String type) {
		return this.jdbc.sql("SELECT count(*) FROM notification.notification WHERE type = :type AND account_id = :id")
			.param("type", type).param("id", this.lan).query(Long.class).single();
	}

}
