package vn.vibefix.repo.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.sql.Timestamp;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;

import vn.vibefix.identity.internal.IdentityTestSupport;
import vn.vibefix.identity.internal.Role;
import vn.vibefix.job.JobTransitioned;
import vn.vibefix.support.IntegrationTest;
import vn.vibefix.timers.internal.DeadlinePoller;

/**
 * Story 23.10b (FR-21, FR-38, D10, AD-7): what a Job that ends with its handover work still only in the
 * Intermediary Repository leaves the Customer. On the ending transition, the one that schedules the repositories'
 * deletion 30 days out, the Customer gets the to-do {@code repo.download_before_deletion} with that date, a
 * {@code repo.download_reminder} Deadline for each lead time before it, and -- when the closing choice's deadline
 * was what ended the Job -- {@code repo.code_receipt_unconfirmed}.
 * <p>
 * Each reminder re-checks at fire time: a code receipt or a Final Merge read back in between, or repositories
 * already gone, leaves it {@code OBSOLETE}. An Assessment Job has no handover work, so it gets no to-do, no
 * reminder and no notice.
 */
class DownloadReminderIT extends IntegrationTest {

	private static final AtomicLong IDS = new AtomicLong(770000);

	@Autowired
	private JdbcClient jdbc;

	@Autowired
	private TransactionTemplate transactions;

	@Autowired
	private ApplicationEventPublisher events;

	@Autowired
	private DeadlinePoller poller;

	@Autowired
	private RepositoryDeletions deletions;

	private UUID lan;

	private UUID job;

	private UUID setup;

	@BeforeEach
	void aCodeJobWhoseRepositoriesStillStand() {
		this.lan = IdentityTestSupport.insertAccount(this.jdbc, Role.CUSTOMER, null);
		UUID mentor = IdentityTestSupport.insertAccount(this.jdbc, Role.MENTOR, null);
		long id = IDS.incrementAndGet();
		UUID product = UUID.randomUUID();
		this.jdbc.sql("""
				INSERT INTO product.product (id, customer_account_id, name, description, ai_tools, repository_github_id,
				    repository_full_name, repository_owner_type, repository_default_branch, public_notice_version,
				    copy_permission_version, detection_notice_version, acknowledged_at, created_at)
				VALUES (:id, :owner, 'Sổ đặt bàn Quán Mây', 'Đặt bàn.', ARRAY['LOVABLE'], :github, :name, 'User', 'main',
				    '1.0', '1.0', '1.0', now(), now())
				""").param("id", product).param("owner", this.lan).param("github", id)
			.param("name", "quan-may-" + id + "/so-dat-ban").update();
		this.job = UUID.randomUUID();
		Timestamp now = Timestamp.from(this.clock.instant());
		this.jdbc.sql("""
				INSERT INTO job.job (id, product_id, customer_account_id, state, title, problem, expected, app_users,
				    job_types, budget, deadline, rules, selection_days, opened_at, lapses_at, mentor_account_id,
				    selected_application_id, selected_at, version, created_at, updated_at, closing_at)
				VALUES (:id, :product, :owner, 'CLOSED', 'Sửa lỗi đặt bàn', 'Lỗi', 'Đúng', 'Khách', ARRAY['BUG_FIX'],
				    'UNDER_2M', DATE '2026-12-01', '{}'::jsonb, 14, :now, :now, :mentor, :application, :now, 9, :now, :now,
				    :now)
				""").param("id", this.job).param("product", product).param("owner", this.lan).param("mentor", mentor)
			.param("application", UUID.randomUUID()).param("now", now).update();
		this.setup = UUID.randomUUID();
		this.jdbc.sql("""
				INSERT INTO repo.job_setup (id, job_id, product_id, status, source_full_name, default_branch, head_sha,
				    head_read_at, approved_sha, confirmed_at, intermediary_github_id, intermediary_name, working_github_id,
				    working_name, checked_at, intermediary_at, working_at, copied_at, started_at, finished_at)
				VALUES (:id, :job, :product, 'DONE', :source, 'main', :sha, :now, :sha, :now, :intermediaryId, :intermediary,
				    :workingId, :working, :now, :now, :now, :now, :now, :now)
				""").param("id", this.setup).param("job", this.job).param("product", product)
			.param("source", "quan-may-" + id + "/so-dat-ban").param("sha", "a".repeat(40))
			.param("intermediaryId", id + 1).param("intermediary", "vibefix-dev/job-" + id + "-intermediary")
			.param("workingId", id + 2).param("working", "vibefix-dev/job-" + id + "-working").param("now", now).update();
	}

	/**
	 * The ending leaves one to-do naming the deletion date, one notice, and one Deadline per lead time; each
	 * reminder then fires once and names the same date.
	 */
	@Test
	void theEndingOpensTheDutyAndBothRemindersFireOnceEach() {
		acceptedWork();
		ends("UNCONFIRMED");
		await().atMost(Duration.ofSeconds(15)).until(() -> downloadTodos() == 1);

		assertThat(this.jdbc.sql("""
				SELECT kind FROM timers.deadline WHERE job_id = :job AND kind LIKE 'repo.%' ORDER BY due_at
				""").param("job", this.job).query(String.class).list())
			.as("the two reminders, then the deletion itself")
			.containsExactly("repo.download_reminder", "repo.download_reminder", "repo.repository_deletion");
		assertThat(this.jdbc.sql("""
				SELECT generation FROM timers.deadline WHERE job_id = :job AND kind = 'repo.download_reminder'
				ORDER BY due_at
				""").param("job", this.job).query(String.class).list()).containsExactly("7", "1");
		assertThat(this.jdbc.sql("""
				SELECT due_at FROM timers.deadline WHERE job_id = :job AND kind = 'repo.repository_deletion'
				""").param("job", this.job).query(Timestamp.class).single().toInstant())
			.isEqualTo(this.clock.instant().plus(Revocations.KEEP));
		String date = todoParam("date");
		assertThat(date).as("the to-do names the deletion date").isNotBlank();
		assertThat(todoDeadline()).as("and carries it as its own deadline")
			.isEqualTo(Timestamp.from(this.clock.instant().plus(Revocations.KEEP)));
		assertThat(notificationDates("repo.code_receipt_unconfirmed")).containsExactly(date);

		this.clock.advance(Duration.ofDays(23).plusMinutes(1));
		this.poller.runDue();
		assertThat(notificationDates("repo.download_reminder")).as("7 days before: once").containsExactly(date);
		assertThat(reminderStatuses()).containsExactly("FIRED", "PENDING");

		this.clock.advance(Duration.ofDays(6));
		this.poller.runDue();
		assertThat(notificationDates("repo.download_reminder")).as("1 day before: once more").containsExactly(date, date);
		assertThat(reminderStatuses()).containsExactly("FIRED", "FIRED");
	}

	/** A Job Cancelled at J10 never had a choice to miss: it gets the duty and the reminders, and no notice. */
	@Test
	void aCancelledJobGetsTheDutyWithoutTheUnconfirmedNotice() {
		acceptedWork();
		this.jdbc.sql("UPDATE job.job SET state = 'CANCELLED' WHERE id = :id").param("id", this.job).update();
		cancels();
		await().atMost(Duration.ofSeconds(15)).until(() -> downloadTodos() == 1);

		assertThat(reminderDeadlines()).isEqualTo(2);
		assertThat(notificationDates("repo.code_receipt_unconfirmed")).isEmpty();
	}

	/** The Customer said they have the code: every reminder still to come is obsolete and nothing is sent. */
	@Test
	void aRecordedReceiptSilencesEveryReminderStillToCome() {
		acceptedWork();
		ends("UNCONFIRMED");
		await().atMost(Duration.ofSeconds(15)).until(() -> reminderDeadlines() == 2);
		this.jdbc.sql("""
				INSERT INTO repo.code_receipt (job_id, setup_id, confirmed_by, confirmed_at)
				VALUES (:job, :setup, :by, now())
				""").param("job", this.job).param("setup", this.setup).param("by", this.lan).update();

		this.clock.advance(Duration.ofDays(29).plusMinutes(1));
		this.poller.runDue();

		assertThat(notificationDates("repo.download_reminder")).isEmpty();
		assertThat(reminderStatuses()).as("both were due, both are obsolete").containsExactly("OBSOLETE", "OBSOLETE");
	}

	/** The Final Merge branch was read back: the code is in the Customer's own project, so nothing is sent. */
	@Test
	void aFinalMergeReadBackSilencesTheReminders() {
		acceptedWork();
		ends("UNCONFIRMED");
		await().atMost(Duration.ofSeconds(15)).until(() -> reminderDeadlines() == 2);
		UUID authorization = UUID.randomUUID();
		this.jdbc.sql("""
				INSERT INTO repo.final_merge_authorization (id, job_id, product_id, customer_account_id,
				    repository_github_id, repository_name, status, requested_at, ended_at)
				SELECT :id, id, product_id, customer_account_id, 1, 'quan-may/so-dat-ban', 'USED', now(), now()
				FROM job.job WHERE id = :job
				""").param("id", authorization).param("job", this.job).update();
		this.jdbc.sql("""
				INSERT INTO repo.final_merge (authorization_id, job_id, product_id, status, merged_at, created_at)
				SELECT :id, job_id, product_id, 'DONE', now(), now() FROM repo.final_merge_authorization WHERE id = :id
				""").param("id", authorization).update();

		this.clock.advance(Duration.ofDays(29).plusMinutes(1));
		this.poller.runDue();

		assertThat(notificationDates("repo.download_reminder")).isEmpty();
	}

	/** The repositories are gone before the reminder's day: there is nothing left to fetch. */
	@Test
	void deletedRepositoriesLeaveTheReminderObsolete() {
		acceptedWork();
		ends("UNCONFIRMED");
		await().atMost(Duration.ofSeconds(15)).until(() -> reminderDeadlines() == 2);
		this.jdbc.sql("UPDATE repo.job_setup SET deleted_at = now() WHERE id = :id").param("id", this.setup).update();

		this.clock.advance(Duration.ofDays(29).plusMinutes(1));
		this.poller.runDue();

		assertThat(notificationDates("repo.download_reminder")).isEmpty();
	}

	/**
	 * Nothing was ever merged into the Intermediary Repository, so there is nothing to fetch: no duty, no reminder
	 * and no notice. That is every Assessment Job, which has no handover work at all (D10), and a Code Job that
	 * ended before any handover was accepted.
	 */
	@Test
	void aJobWithNoHandoverWorkGetsNothing() {
		ends("UNCONFIRMED");
		await().atMost(Duration.ofSeconds(15)).until(() -> this.jdbc.sql("""
				SELECT count(*) FROM timers.deadline WHERE job_id = :job AND kind = 'repo.repository_deletion'
				""").param("job", this.job).query(Long.class).single() == 1);

		assertThat(downloadTodos()).isZero();
		assertThat(reminderDeadlines()).isZero();
		assertThat(notificationDates("repo.code_receipt_unconfirmed")).isEmpty();
	}

	/**
	 * The closure fallback stored its ZIP, so the code reached the Customer as a file of vibefix's own (Story 8.9):
	 * there is nothing to fetch from GitHub, so no duty, no reminder and no notice.
	 */
	@Test
	void aJobEndedWithItsZipStoredGetsNothing() {
		acceptedWork();
		UUID authorization = UUID.randomUUID();
		this.jdbc.sql("""
				INSERT INTO repo.final_merge_authorization (id, job_id, product_id, customer_account_id,
				    repository_github_id, repository_name, status, requested_at, ended_at, end_reason)
				SELECT :id, id, product_id, customer_account_id, 2, 'quan-may/so-dat-ban', 'ENDED', now(), now(),
				    'ZIP_FALLBACK'
				FROM job.job WHERE id = :job
				""").param("id", authorization).param("job", this.job).update();
		// A READY row names its archive; the file itself is the closure fallback's, and nothing here reads it.
		this.jdbc.sql("""
				INSERT INTO repo.zip_fallback (job_id, authorization_id, product_id, reason, status, archive_file_id,
				    created_at, ready_at)
				SELECT job_id, id, product_id, 'MERGE_REFUSED', 'READY', :file, now(), now()
				FROM repo.final_merge_authorization WHERE id = :id
				""").param("id", authorization).param("file", UUID.randomUUID()).update();

		ends("ZIP_FALLBACK");
		await().atMost(Duration.ofSeconds(15)).until(() -> this.jdbc.sql("""
				SELECT count(*) FROM timers.deadline WHERE job_id = :job AND kind = 'repo.repository_deletion'
				""").param("job", this.job).query(Long.class).single() == 1);

		assertThat(downloadTodos()).isZero();
		assertThat(reminderDeadlines()).isZero();
		assertThat(notificationDates("repo.code_receipt_unconfirmed")).isEmpty();
	}

	/**
	 * FR-21: the repositories are read back as gone on the deletion day. Nothing can close the fetching to-do after
	 * that -- a code receipt needs a readable Intermediary Repository -- so the deletion closes it itself.
	 */
	@Test
	void theDeletionClosesTheFetchingTodo() {
		acceptedWork();
		ends("UNCONFIRMED");
		await().atMost(Duration.ofSeconds(15)).until(() -> downloadTodos() == 1);

		this.transactions.executeWithoutResult(status -> this.deletions.deleted(this.setup, this.job, null));

		assertThat(downloadTodos()).isZero();
	}

	/** One accepted handover merged into the Intermediary default branch: the code the Customer may still take. */
	private void acceptedWork() {
		this.jdbc.sql("""
				INSERT INTO repo.handover_merge (job_id, pull_number, handover_sha, head_after, merged_at)
				VALUES (:job, 1, :sha, :sha, :now)
				""").param("job", this.job).param("sha", "b".repeat(40))
			.param("now", Timestamp.from(this.clock.instant())).update();
	}

	/** J9 as the closing choice's deadline applies it, or as a confirmed receipt would, by its {@code finish} fact. */
	private void ends(String finish) {
		publish(new JobTransitioned(UUID.randomUUID(), UUID.randomUUID(), this.job, "J9", "SYSTEM", "CLOSING", "CLOSED", 9,
				1, Map.of("finish", finish)));
	}

	private void cancels() {
		publish(new JobTransitioned(UUID.randomUUID(), UUID.randomUUID(), this.job, "J10", "USER", "ACTIVE", "CANCELLED", 9,
				1, Map.of()));
	}

	private void publish(JobTransitioned event) {
		this.transactions.executeWithoutResult(status -> this.events.publishEvent(event));
	}

	private long downloadTodos() {
		return this.jdbc.sql("""
				SELECT count(*) FROM notification.todo WHERE todo_key = 'repo.download_before_deletion'
				    AND account_id = :id AND closed_at IS NULL
				""").param("id", this.lan).query(Long.class).single();
	}

	/** The two reminders, the 7-day one first; {@code due_at} is cleared once a Deadline finishes, so it cannot order. */
	private List<String> reminderStatuses() {
		return this.jdbc.sql("""
				SELECT status FROM timers.deadline WHERE job_id = :job AND kind = 'repo.download_reminder'
				ORDER BY generation DESC
				""").param("job", this.job).query(String.class).list();
	}

	private long reminderDeadlines() {
		return this.jdbc.sql("SELECT count(*) FROM timers.deadline WHERE job_id = :job AND kind = 'repo.download_reminder'")
			.param("job", this.job)
			.query(Long.class)
			.single();
	}

	private String todoParam(String name) {
		return this.jdbc.sql("""
				SELECT params ->> :name FROM notification.todo WHERE todo_key = 'repo.download_before_deletion'
				    AND account_id = :id
				""").param("name", name).param("id", this.lan).query(String.class).single();
	}

	private Timestamp todoDeadline() {
		return this.jdbc.sql("""
				SELECT deadline_at FROM notification.todo WHERE todo_key = 'repo.download_before_deletion'
				    AND account_id = :id
				""").param("id", this.lan).query(Timestamp.class).single();
	}

	private List<String> notificationDates(String type) {
		return this.jdbc.sql("""
				SELECT params ->> 'date' FROM notification.notification WHERE type = :type AND account_id = :id
				ORDER BY created_at
				""").param("type", type).param("id", this.lan).query(String.class).list();
	}

}
