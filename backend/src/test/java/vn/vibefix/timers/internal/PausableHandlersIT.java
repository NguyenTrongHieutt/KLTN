package vn.vibefix.timers.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;

import vn.vibefix.identity.internal.IdentityTestSupport;
import vn.vibefix.identity.internal.Role;
import vn.vibefix.job.JobFreezes;
import vn.vibefix.support.IntegrationTest;
import vn.vibefix.support.ScriptedDeadlineHandler;
import vn.vibefix.timers.DeadlineHandler;
import vn.vibefix.timers.DeadlineKind;
import vn.vibefix.timers.DeadlineRequest;
import vn.vibefix.timers.Deadlines;

/**
 * A-02, AD-4, AD-7: the handler of every pausable kind takes the Job row lock first and answers REPAUSE on
 * a frozen Job before it looks at anything else, so a subject that no longer exists, here none at all, is
 * never OBSOLETE before the thaw. Afterwards each one runs with its remaining time.
 */
class PausableHandlersIT extends IntegrationTest {

	@Autowired
	private List<DeadlineHandler> handlers;

	@Autowired
	private Deadlines deadlines;

	@Autowired
	private JobFreezes freezes;

	@Autowired
	private DeadlinePoller poller;

	@Autowired
	private JdbcClient jdbc;

	@Autowired
	private TransactionTemplate transactions;

	@Test
	void everyPausableKindRepausesOnAFrozenJobAndRunsWithItsRemainingTimeAfterTheThaw() {
		UUID job = activeJob();
		Instant t0 = this.clock.instant();
		List<String> kinds = this.handlers.stream()
			.filter(handler -> !(handler instanceof ScriptedDeadlineHandler))
			.map(DeadlineHandler::kind)
			.filter(DeadlineKind::pausable)
			.map(DeadlineKind::key)
			.toList();
		assertThat(kinds).containsExactlyInAnyOrder("delivery.auto_acceptance", "delivery.acceptance_reminder",
				"job.agreement_deadline", "job.starting_deadline", "job.cancellation_answer", "job.closure_answer",
				"job.closure_auto_confirm", "job.milestone_due_soon", "job.milestone_overdue", "job.milestone_unfunded",
				"repo.final_merge_deadline", "repo.final_merge_reminder");
		this.transactions.executeWithoutResult(status -> kinds.forEach(kind -> this.deadlines
			.schedule(new DeadlineRequest(kind, job, job.toString(), job, t0.plus(Duration.ofHours(10))))));
		// The freeze could not lock them: they stay pending, for their handlers.
		Timestamp frozen = Timestamp.from(t0.plus(Duration.ofHours(4)));
		this.jdbc.sql("UPDATE job.job SET frozen_at = :at WHERE id = :id").param("at", frozen).param("id", job).update();
		this.jdbc.sql("INSERT INTO timers.frozen_job (job_id, frozen_at) VALUES (:job, :at)")
			.param("job", job).param("at", frozen).update();

		this.clock.advance(Duration.ofHours(10));
		assertThat(this.poller.runDue()).isEqualTo(kinds.size());

		assertThat(rows(job, "status || ':' || remaining_ms")).containsExactlyInAnyOrderElementsOf(
				kinds.stream().map(kind -> kind + " PAUSED:" + Duration.ofHours(6).toMillis()).toList());
		this.clock.advance(Duration.ofMinutes(1));
		assertThat(this.poller.runDue()).as("they wait for the thaw").isZero();

		this.clock.advance(Duration.ofHours(1));
		this.transactions.executeWithoutResult(status -> {
			this.freezes.unfreeze(job);
			this.deadlines.unfreeze(job);
		});
		assertThat(this.jdbc.sql("SELECT DISTINCT due_at FROM timers.deadline WHERE job_id = :job").param("job", job)
			.query(Timestamp.class).list()).containsExactly(Timestamp.from(this.clock.instant().plus(Duration.ofHours(6))));

		this.clock.advance(Duration.ofHours(6));
		assertThat(this.poller.runDue()).isEqualTo(kinds.size());
		assertThat(rows(job, "status")).as("not frozen any more: none of them has a real subject")
			.containsExactlyInAnyOrderElementsOf(kinds.stream().map(kind -> kind + " OBSOLETE").toList());
	}

	private List<String> rows(UUID job, String column) {
		return this.jdbc.sql("SELECT kind || ' ' || " + column + " FROM timers.deadline WHERE job_id = :job")
			.param("job", job)
			.query(String.class)
			.list();
	}

	private UUID activeJob() {
		UUID customer = IdentityTestSupport.insertAccount(this.jdbc, Role.CUSTOMER, null);
		UUID mentor = IdentityTestSupport.insertAccount(this.jdbc, Role.MENTOR, null);
		UUID product = UUID.randomUUID();
		this.jdbc.sql("""
				INSERT INTO product.product (id, customer_account_id, name, description, ai_tools, repository_github_id,
				    repository_full_name, repository_owner_type, repository_default_branch, public_notice_version,
				    copy_permission_version, detection_notice_version, acknowledged_at, created_at)
				VALUES (:id, :owner, 'Sổ đặt bàn', 'Đặt bàn.', ARRAY['LOVABLE'], :github, 'lan/so-dat-ban', 'User', 'main',
				    '1.0', '1.0', '1.0', now(), now())
				""").param("id", product).param("owner", customer)
			.param("github", ThreadLocalRandom.current().nextLong(1, Long.MAX_VALUE)).update();
		UUID job = UUID.randomUUID();
		Timestamp now = Timestamp.from(this.clock.instant());
		this.jdbc.sql("""
				INSERT INTO job.job (id, product_id, customer_account_id, state, title, problem, expected, app_users,
				    job_types, budget, deadline, rules, selection_days, opened_at, lapses_at, mentor_account_id,
				    selected_application_id, selected_at, version, created_at, updated_at)
				VALUES (:id, :product, :owner, 'ACTIVE', 'Sổ đặt bàn', 'Lỗi', 'Đúng', 'Khách', ARRAY['BUG_FIX'],
				    'UNDER_2M', DATE '2026-12-01', '{}'::jsonb, 14, :now, :now, :mentor, :application, :now, 5, :now, :now)
				""").param("id", job).param("product", product).param("owner", customer).param("mentor", mentor)
			.param("application", UUID.randomUUID()).param("now", now).update();
		return job;
	}

}
