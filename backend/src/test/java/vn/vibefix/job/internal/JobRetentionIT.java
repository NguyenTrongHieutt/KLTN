package vn.vibefix.job.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;

import vn.vibefix.identity.MentorApprovalStatus;
import vn.vibefix.identity.internal.IdentityTestSupport;
import vn.vibefix.job.JobTransitioned;
import vn.vibefix.shared.Role;
import vn.vibefix.support.IntegrationTest;
import vn.vibefix.timers.internal.DeadlinePoller;

/**
 * Story 13.5 (NFR-10, AD-24, NFR-7): a Job reaching Closed or Cancelled schedules its Product's retention 12
 * months later; another Job on the Product past Draft supersedes it, and a later end takes over. When it
 * fires, the Product's Job data is retired (Pages, External Access identifiers) while the Product, the
 * ledger, and the Audit Log stay.
 */
class JobRetentionIT extends IntegrationTest {

	@Autowired
	private JdbcClient jdbc;

	@Autowired
	private TransactionTemplate transactions;

	@Autowired
	private ApplicationEventPublisher events;

	@Autowired
	private DeadlinePoller poller;

	private UUID lan;

	private UUID hung;

	private UUID product;

	private UUID first;

	@BeforeEach
	void aProductWhoseOnlyJobJustClosed() {
		this.lan = IdentityTestSupport.insertAccount(this.jdbc, Role.CUSTOMER, null, null);
		this.hung = IdentityTestSupport.insertAccount(this.jdbc, Role.MENTOR, MentorApprovalStatus.APPROVED, null);
		this.product = UUID.randomUUID();
		this.jdbc.sql("""
				INSERT INTO product.product (id, customer_account_id, name, description, ai_tools, repository_github_id,
				    repository_full_name, repository_owner_type, repository_default_branch, public_notice_version,
				    copy_permission_version, detection_notice_version, acknowledged_at, created_at)
				VALUES (:id, :owner, 'Sổ đặt bàn Quán Mây', 'Đặt bàn.', ARRAY['LOVABLE'], :github, 'lan/so-dat-ban', 'User', 'main',
				    '1.0', '1.0', '1.0', now(), now())
				""").param("id", this.product).param("owner", this.lan)
			.param("github", ThreadLocalRandom.current().nextLong(1, Long.MAX_VALUE)).update();
		this.first = job("CLOSED");
	}

	@Test
	void twelveMonthsAfterTheLastJobEndedItsJobDataIsRetiredAndTheRecordsStay() {
		ended(this.first, "CLOSED");
		await().atMost(Duration.ofSeconds(15)).until(() -> this.jdbc.sql("""
				SELECT count(*) FROM timers.deadline WHERE kind = 'job.retention' AND subject_id = :product
				""").param("product", this.product).query(Long.class).single() == 1);
		Instant due = this.jdbc.sql("SELECT due_at FROM timers.deadline WHERE kind = 'job.retention' AND subject_id = :product")
			.param("product", this.product).query(Timestamp.class).single().toInstant();
		assertThat(due).isAfter(this.clock.instant().plus(Duration.ofDays(364)))
			.isBefore(this.clock.instant().plus(Duration.ofDays(367)));
		long audits = this.jdbc.sql("SELECT count(*) FROM audit.audit_entry").query(Long.class).single();

		this.clock.advance(Duration.ofDays(360));
		this.poller.runDue();
		assertThat(pages()).as("not yet").isOne();

		this.clock.advance(Duration.ofDays(7));
		this.poller.runDue();

		assertThat(pages()).isZero();
		assertThat(this.jdbc.sql("SELECT count(*) FROM job.external_access_identifier WHERE job_id = :job")
			.param("job", this.first).query(Long.class).single()).isZero();
		assertThat(this.jdbc.sql("SELECT count(*) FROM work.board WHERE job_id = :job").param("job", this.first)
			.query(Long.class).single()).as("the Board stays, empty").isOne();
		assertThat(this.jdbc.sql("SELECT count(*) FROM job.job WHERE id = :job").param("job", this.first).query(Long.class)
			.single()).isOne();
		assertThat(this.jdbc.sql("SELECT count(*) FROM product.product WHERE id = :id").param("id", this.product)
			.query(Long.class).single()).as("the Product stays").isOne();
		assertThat(this.jdbc.sql("SELECT count(*) FROM payment.ledger_entry WHERE product_id = :id").param("id", this.product)
			.query(Long.class).single()).as("the ledger stays").isOne();
		assertThat(this.jdbc.sql("SELECT count(*) FROM audit.audit_entry").query(Long.class).single())
			.as("the Audit Log only grows").isGreaterThan(audits);
		assertThat(this.jdbc.sql("SELECT count(*) FROM audit.audit_entry WHERE action = 'job.retention_deleted'")
			.query(Long.class).single()).isOne();
		assertThat(this.jdbc.sql("SELECT count(*) FROM job.product_retention WHERE product_id = :id").param("id", this.product)
			.query(Long.class).single()).isOne();
	}

	@Test
	void anotherJobPastDraftSupersedesItAndALaterEndTakesOver() {
		ended(this.first, "CLOSED");
		await().atMost(Duration.ofSeconds(15)).until(() -> deadlines() == 1);
		UUID second = job("SELECTING");

		this.clock.advance(Duration.ofDays(370));
		this.poller.runDue();
		assertThat(pages()).as("another Job is running").isOne();
		assertThat(this.jdbc.sql("SELECT status FROM timers.deadline WHERE kind = 'job.retention' AND generation = :job")
			.param("job", this.first.toString()).query(String.class).single()).isEqualTo("OBSOLETE");

		this.jdbc.sql("UPDATE job.job SET state = 'CANCELLED', cancelled_at = :now WHERE id = :id")
			.param("now", Timestamp.from(this.clock.instant())).param("id", second).update();
		ended(second, "CANCELLED");
		await().atMost(Duration.ofSeconds(15)).until(() -> deadlines() == 2);
		this.clock.advance(Duration.ofDays(367));
		this.poller.runDue();
		assertThat(pages()).isZero();
	}

	private UUID job(String state) {
		UUID id = UUID.randomUUID();
		Timestamp now = Timestamp.from(this.clock.instant());
		this.jdbc.sql("""
				INSERT INTO job.job (id, product_id, customer_account_id, state, title, problem, expected, app_users,
				    job_types, budget, deadline, rules, selection_days, opened_at, lapses_at, mentor_account_id,
				    selected_application_id, selected_at, version, created_at, updated_at, closed_at)
				VALUES (:id, :product, :owner, :state, 'Sửa lỗi đặt bàn', 'Lỗi', 'Đúng', 'Khách', ARRAY['BUG_FIX'],
				    'UNDER_2M', DATE '2026-12-01', '{}'::jsonb, 14, :now, :now, :mentor, :application, :now, 9, :now, :now,
				    :closed)
				""").param("id", id).param("product", this.product).param("owner", this.lan).param("state", state)
			.param("mentor", this.hung).param("application", UUID.randomUUID()).param("now", now)
			.param("closed", "CLOSED".equals(state) ? now : null).update();
		if (!"CLOSED".equals(state)) {
			return id;
		}
		this.jdbc.sql("""
				INSERT INTO work.board (job_id, product_id, code, next_number, created_at) VALUES (:job, :product, 'SDB', 1, :now)
				""").param("job", id).param("product", this.product).param("now", now).update();
		UUID page = UUID.randomUUID();
		this.jdbc.sql("""
				INSERT INTO work.page (id, job_id, title, version_no, created_at, updated_at, visibility)
				VALUES (:id, :job, 'Hướng dẫn dùng', 1, :now, :now, 'CUSTOMER')
				""").param("id", page).param("job", id).param("now", now).update();
		this.jdbc.sql("""
				INSERT INTO work.page_version (page_id, version_no, title, body, created_at) VALUES (:id, 1, 'Hướng dẫn dùng', 'Nội dung', :now)
				""").param("id", page).param("now", now).update();
		UUID access = UUID.randomUUID();
		this.jdbc.sql("""
				INSERT INTO job.external_access (id, job_id, position, system, purpose, created_at)
				VALUES (:id, :job, 0, 'Supabase', 'Xem bảng', :now)
				""").param("id", access).param("job", id).param("now", now).update();
		this.jdbc.sql("""
				INSERT INTO job.external_access_identifier (access_id, account_id, job_id, identifier, updated_at)
				VALUES (:access, :mentor, :job, 'hung@supabase.test', :now)
				""").param("access", access).param("mentor", this.hung).param("job", id).param("now", now).update();
		UUID milestone = UUID.randomUUID();
		this.jdbc.sql("""
				INSERT INTO payment.ledger_account (milestone_id, job_id, product_id, created_at) VALUES (:id, :job, :product, now())
				""").param("id", milestone).param("job", id).param("product", this.product).update();
		this.jdbc.sql("""
				INSERT INTO payment.ledger_entry (id, operation_key, entry_type, bucket, job_id, product_id, milestone_id, amount_vnd,
				    vnp_transaction_no, created_at)
				VALUES (:id, :key, 'FUND', 'MILESTONE', :job, :product, :milestone, 500000, :no, now())
				""").param("id", UUID.randomUUID()).param("key", "FUND:" + UUID.randomUUID()).param("job", id)
			.param("product", this.product).param("milestone", milestone)
			.param("no", String.valueOf(ThreadLocalRandom.current().nextLong(1, Long.MAX_VALUE))).update();
		return id;
	}

	private void ended(UUID job, String state) {
		this.transactions.executeWithoutResult(status -> this.events.publishEvent(new JobTransitioned(UUID.randomUUID(),
				UUID.randomUUID(), job, "CLOSED".equals(state) ? "J9" : "J10", "SYSTEM", "CLOSING", state, 10, 1, Map.of())));
	}

	private long pages() {
		return this.jdbc.sql("SELECT count(*) FROM work.page WHERE job_id = :job").param("job", this.first).query(Long.class)
			.single();
	}

	private long deadlines() {
		return this.jdbc.sql("SELECT count(*) FROM timers.deadline WHERE kind = 'job.retention' AND subject_id = :product")
			.param("product", this.product).query(Long.class).single();
	}

}
