package vn.vibefix.timers.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;

import vn.vibefix.dispute.internal.DisputeTestSupport;
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
 * A-02, AD-4, AD-7, over the 11 pausable kinds, each with a subject that does not exist, except the two whose
 * subject is the Job. The first test freezes the Job before the poll: it proves that the frozen check comes
 * before any look at the subject and that nothing runs before the thaw. It does not show a handler locks,
 * since one reading the freeze unlocked passes too, or that a rule fires, since without a real subject each
 * ends {@code OBSOLETE}. The second races a Dispute with the poller: it proves the handler decides under the
 * lock of the Deadline's own Job, that the row pauses with its remaining time, and that the thaw makes it due
 * at that time. Other tests make real Deadlines fire after the thaw.
 */
class PausableHandlersIT extends IntegrationTest {

	private static final List<String> KINDS = List.of("delivery.auto_acceptance", "delivery.acceptance_reminder",
			"job.agreement_deadline", "job.starting_deadline", "job.cancellation_answer", "job.closure_answer",
			"job.closure_auto_confirm", "job.milestone_due_soon", "job.milestone_overdue", "job.milestone_unfunded",
			"repo.final_merge_deadline");

	/** These two take the Job as their subject (FR-40). */
	private static final Set<String> SUBJECT_IS_THE_JOB = Set.of("job.agreement_deadline", "job.starting_deadline");

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

	@Autowired
	private ApplicationContext context;

	@Test
	void everyPausableKindRepausesOnAJobFrozenBeforeThePollAndRunsAfterTheThaw() {
		assertThat(this.handlers.stream()
			.filter(handler -> !(handler instanceof ScriptedDeadlineHandler))
			.map(DeadlineHandler::kind)
			.filter(DeadlineKind::pausable)
			.map(DeadlineKind::key)).containsExactlyInAnyOrderElementsOf(KINDS);
		UUID job = activeJob(IdentityTestSupport.insertAccount(this.jdbc, Role.CUSTOMER, null));
		Instant t0 = this.clock.instant();
		KINDS.forEach(kind -> schedule(kind, job, t0.plus(Duration.ofHours(10))));
		// The freeze could not lock them: they stay pending, for their handlers.
		Timestamp frozen = Timestamp.from(t0.plus(Duration.ofHours(4)));
		this.jdbc.sql("UPDATE job.job SET frozen_at = :at WHERE id = :id").param("at", frozen).param("id", job).update();
		this.jdbc.sql("INSERT INTO timers.frozen_job (job_id, frozen_at) VALUES (:job, :at)")
			.param("job", job).param("at", frozen).update();

		this.clock.advance(Duration.ofHours(10));
		assertThat(this.poller.runDue()).isEqualTo(KINDS.size());

		assertThat(rows(job, "status || ':' || remaining_ms")).containsExactlyInAnyOrderElementsOf(
				KINDS.stream().map(kind -> kind + " PAUSED:" + Duration.ofHours(6).toMillis()).toList());
		this.clock.advance(Duration.ofMinutes(1));
		assertThat(this.poller.runDue()).as("they wait for the thaw").isZero();

		this.clock.advance(Duration.ofHours(1));
		thaw(job);
		assertThat(this.jdbc.sql("SELECT DISTINCT due_at FROM timers.deadline WHERE job_id = :job").param("job", job)
			.query(Timestamp.class).list()).containsExactly(Timestamp.from(this.clock.instant().plus(Duration.ofHours(6))));

		this.clock.advance(Duration.ofHours(6));
		assertThat(this.poller.runDue()).isEqualTo(KINDS.size());
		assertThat(rows(job, "status")).as("not frozen any more, and no subject to run on")
			.containsExactlyInAnyOrderElementsOf(KINDS.stream().map(kind -> kind + " OBSOLETE").toList());
	}

	@ParameterizedTest
	@MethodSource("kinds")
	void everyPausableKindRacingADisputePauses(String kind) throws Exception {
		UUID customer = IdentityTestSupport.insertAccount(this.jdbc, Role.CUSTOMER, null);
		UUID job = activeJob(customer);
		Instant t0 = this.clock.instant();
		Instant due = t0.plus(Duration.ofHours(10));
		UUID deadline = schedule(kind, job, due);

		this.clock.setInstant(due);
		DisputeTestSupport.openRacingThePoller(this.context, job, customer, deadline, t0.plus(Duration.ofHours(4)));

		assertThat(row(deadline)).containsEntry("status", "PAUSED")
			.containsEntry("remaining_ms", Duration.ofHours(6).toMillis());

		this.clock.setInstant(due.plus(Duration.ofHours(1)));
		thaw(job);
		assertThat(row(deadline)).containsEntry("status", "PENDING")
			.containsEntry("due_at", Timestamp.from(this.clock.instant().plus(Duration.ofHours(6))));
	}

	static List<String> kinds() {
		return KINDS;
	}

	private UUID schedule(String kind, UUID job, Instant due) {
		UUID subject = SUBJECT_IS_THE_JOB.contains(kind) ? job : UUID.randomUUID();
		return this.transactions.execute(status -> this.deadlines
			.schedule(new DeadlineRequest(kind, subject, subject.toString(), job, due)));
	}

	private void thaw(UUID job) {
		this.transactions.executeWithoutResult(status -> {
			this.freezes.unfreeze(job);
			this.deadlines.unfreeze(job);
		});
	}

	private Map<String, Object> row(UUID id) {
		return this.jdbc.sql("SELECT * FROM timers.deadline WHERE id = :id").param("id", id).query().singleRow();
	}

	private List<String> rows(UUID job, String column) {
		return this.jdbc.sql("SELECT kind || ' ' || " + column + " FROM timers.deadline WHERE job_id = :job")
			.param("job", job)
			.query(String.class)
			.list();
	}

	private UUID activeJob(UUID customer) {
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
