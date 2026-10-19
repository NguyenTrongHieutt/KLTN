package vn.vibefix.timers.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import vn.vibefix.support.IntegrationTest;
import vn.vibefix.support.ScriptedDeadlineHandler;
import vn.vibefix.timers.DeadlineOutcome;
import vn.vibefix.timers.DeadlineRequest;
import vn.vibefix.timers.Deadlines;
import vn.vibefix.timers.DueDeadline;

/** AD-7: Deadlines claimed with SKIP LOCKED, the four outcomes, lazy cancellation, and Job freezes. */
class TimersIT extends IntegrationTest {

	private static final UUID JOB = UUID.fromString("0190f0c3-0000-7000-8000-00000000d001");

	private static final UUID OTHER_JOB = UUID.fromString("0190f0c3-0000-7000-8000-00000000d002");

	@Autowired
	private Deadlines deadlines;

	@Autowired
	private DeadlinePoller poller;

	@Autowired
	private ScriptedDeadlineHandler plainDeadlineHandler;

	@Autowired
	private ScriptedDeadlineHandler pausableDeadlineHandler;

	@Autowired
	private JdbcClient jdbc;

	@Autowired
	private PlatformTransactionManager transactionManager;

	private TransactionTemplate tx;

	private Instant t0;

	@BeforeEach
	void start() {
		this.tx = new TransactionTemplate(this.transactionManager);
		this.t0 = this.clock.instant();
	}

	@Test
	void aDueDeadlineRunsItsHandlerOnceAndFiredFinishesIt() {
		UUID id = plain(this.t0.plus(Duration.ofMinutes(5)), "g1");

		assertThat(this.poller.runDue()).as("not due yet").isZero();
		this.clock.advance(Duration.ofMinutes(5));
		assertThat(this.poller.runDue()).isOne();
		assertThat(this.poller.runDue()).isZero();

		assertThat(this.plainDeadlineHandler.handled()).singleElement().satisfies(deadline -> {
			assertThat(deadline.id()).isEqualTo(id);
			assertThat(deadline.generation()).isEqualTo("g1");
			assertThat(deadline.dueAt()).isEqualTo(this.t0.plus(Duration.ofMinutes(5)));
		});
		assertThat(row(id)).containsEntry("status", "FIRED").containsEntry("owner", "test");
		assertThat(row(id).get("finished_at")).isNotNull();
	}

	@Test
	void obsoleteIsTheLazyCancellation() {
		this.plainDeadlineHandler.answer(DeadlineOutcome.OBSOLETE);
		UUID id = plain(this.t0, "stale");

		this.poller.runDue();

		assertThat(row(id)).containsEntry("status", "OBSOLETE");
		assertThat(this.poller.runDue()).isZero();
	}

	@Test
	void rearmMakesItDueAtTheNextPoll() {
		this.plainDeadlineHandler.answer(DeadlineOutcome.REARM);
		UUID id = plain(this.t0, "busy");

		assertThat(this.poller.runDue()).isOne();
		assertThat(row(id)).containsEntry("status", "PENDING")
			.containsEntry("due_at", Timestamp.from(this.t0.plus(Duration.ofSeconds(30))));
		assertThat(this.poller.runDue()).as("not before the next poll").isZero();

		this.clock.advance(Duration.ofSeconds(30));
		assertThat(this.poller.runDue()).isOne();
		assertThat(row(id)).containsEntry("status", "FIRED");
		assertThat(this.plainDeadlineHandler.handled()).hasSize(2);
	}

	@Test
	void aFreezePausesWithTheTimeLeftAndTheUnfreezeResumesIt() {
		UUID id = pausable(JOB, this.t0.plus(Duration.ofHours(10)));
		UUID other = pausable(OTHER_JOB, this.t0.plus(Duration.ofHours(10)));

		this.clock.advance(Duration.ofHours(4));
		this.tx.executeWithoutResult(status -> this.deadlines.freeze(JOB));
		assertThat(row(id)).containsEntry("status", "PAUSED").containsEntry("remaining_ms", Duration.ofHours(6).toMillis());
		assertThat(row(other)).as("another Job's Deadline").containsEntry("status", "PENDING");

		this.clock.advance(Duration.ofHours(1));
		this.tx.executeWithoutResult(status -> this.deadlines.freeze(JOB));
		assertThat(row(id)).as("freezing a frozen Job changes nothing")
			.containsEntry("remaining_ms", Duration.ofHours(6).toMillis());

		this.clock.advance(Duration.ofHours(1));
		this.tx.executeWithoutResult(status -> this.deadlines.unfreeze(JOB));
		assertThat(row(id)).containsEntry("status", "PENDING")
			.containsEntry("due_at", Timestamp.from(this.t0.plus(Duration.ofHours(12))));

		this.clock.setInstant(this.t0.plus(Duration.ofHours(10)));
		assertThat(this.poller.runDue()).as("only the other Job's Deadline").isOne();
		this.clock.setInstant(this.t0.plus(Duration.ofHours(12)));
		assertThat(this.poller.runDue()).isOne();
		assertThat(row(id)).containsEntry("status", "FIRED");
	}

	@Test
	void aPausableDeadlineScheduledDuringAFreezeStartsPaused() {
		this.tx.executeWithoutResult(status -> this.deadlines.freeze(JOB));
		UUID id = pausable(JOB, this.t0.plus(Duration.ofHours(5)));
		UUID plain = plain(this.t0.plus(Duration.ofHours(5)), "g");

		assertThat(row(id)).containsEntry("status", "PAUSED").containsEntry("remaining_ms", Duration.ofHours(5).toMillis());
		assertThat(row(plain)).as("a kind that is not pausable").containsEntry("status", "PENDING");

		this.clock.advance(Duration.ofHours(1));
		this.tx.executeWithoutResult(status -> this.deadlines.unfreeze(JOB));
		assertThat(row(id)).containsEntry("due_at", Timestamp.from(this.t0.plus(Duration.ofHours(6))));
	}

	@Test
	void repausePausesWithTheTimeItHadWhenTheJobFroze() {
		this.pausableDeadlineHandler.answer(DeadlineOutcome.REPAUSE);
		UUID id = pausable(JOB, this.t0.plus(Duration.ofHours(10)));
		// The owner saw the freeze before timers paused the row: the race REPAUSE is for.
		this.jdbc.sql("INSERT INTO timers.frozen_job (job_id, frozen_at) VALUES (:job, :at)")
			.param("job", JOB)
			.param("at", Timestamp.from(this.t0.plus(Duration.ofHours(4))))
			.update();

		this.clock.advance(Duration.ofHours(10));
		assertThat(this.poller.runDue()).isOne();
		assertThat(row(id)).containsEntry("status", "PAUSED").containsEntry("remaining_ms", Duration.ofHours(6).toMillis());

		this.clock.advance(Duration.ofHours(2));
		this.tx.executeWithoutResult(status -> this.deadlines.unfreeze(JOB));
		assertThat(row(id)).containsEntry("due_at", Timestamp.from(this.t0.plus(Duration.ofHours(18))));
	}

	@Test
	void aRepauseWithoutARecordedFreezeIsResumedByTheUnfreeze() {
		this.pausableDeadlineHandler.answer(DeadlineOutcome.REPAUSE);
		UUID id = pausable(JOB, this.t0);

		this.poller.runDue();
		assertThat(row(id)).containsEntry("status", "PAUSED").containsEntry("remaining_ms", 0L);

		this.clock.advance(Duration.ofHours(1));
		this.tx.executeWithoutResult(status -> this.deadlines.unfreeze(JOB));
		assertThat(row(id)).containsEntry("status", "PENDING").containsEntry("due_at", Timestamp.from(this.clock.instant()));
	}

	@Test
	void aFailingHandlerRollsBackAndTriesAgainAtTheNextPoll() {
		this.plainDeadlineHandler.fail(new IllegalStateException("planted failure"));
		UUID id = plain(this.t0, "g");
		UUID next = plain(this.t0.plus(Duration.ofSeconds(1)), "g");
		this.clock.advance(Duration.ofSeconds(1));

		assertThat(this.poller.runDue()).as("the failure and the next one").isEqualTo(2);
		assertThat(row(id)).containsEntry("status", "PENDING")
			.containsEntry("due_at", Timestamp.from(this.clock.instant().plus(Duration.ofSeconds(30))));
		assertThat(row(next)).containsEntry("status", "FIRED");

		this.clock.advance(Duration.ofSeconds(30));
		assertThat(this.poller.runDue()).isOne();
		assertThat(row(id)).containsEntry("status", "FIRED");
	}

	@Test
	void aKindThatIsNotPausableCannotRepause() {
		this.plainDeadlineHandler.answer(DeadlineOutcome.REPAUSE);
		UUID id = plain(this.t0, "g");

		this.poller.runDue();

		assertThat(row(id)).as("treated as a failure").containsEntry("status", "PENDING")
			.containsEntry("due_at", Timestamp.from(this.t0.plus(Duration.ofSeconds(30))));
	}

	@Test
	void twoPollersRunEachDueDeadlineOnce() throws Exception {
		List<UUID> ids = new ArrayList<>();
		for (int i = 0; i < 12; i++) {
			ids.add(plain(this.t0, "g" + i));
		}
		this.plainDeadlineHandler.taking(20);
		ExecutorService pool = Executors.newFixedThreadPool(2);
		try {
			List<Future<Integer>> runs = List.of(pool.submit(this.poller::runDue), pool.submit(this.poller::runDue));
			int claimed = 0;
			for (Future<Integer> run : runs) {
				claimed += run.get();
			}
			assertThat(claimed).isEqualTo(12);
		}
		finally {
			pool.shutdownNow();
		}
		assertThat(this.plainDeadlineHandler.handled()).extracting(DueDeadline::id).containsExactlyInAnyOrderElementsOf(ids);
	}

	@Test
	void schedulingNeedsAKnownKindAJobForAJobScopedKindAndATransaction() {
		assertThatThrownBy(() -> this.tx.executeWithoutResult(status -> this.deadlines
			.schedule(new DeadlineRequest("test.unknown", UUID.randomUUID(), "g", null, this.t0))))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("test.unknown");
		assertThatThrownBy(() -> this.tx.executeWithoutResult(status -> this.deadlines
			.schedule(new DeadlineRequest(ScriptedDeadlineHandler.PAUSABLE.key(), UUID.randomUUID(), "g", null, this.t0))))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("belongs to a Job");
		assertThatThrownBy(() -> this.deadlines
			.schedule(new DeadlineRequest(ScriptedDeadlineHandler.PLAIN.key(), UUID.randomUUID(), "g", null, this.t0)))
			.isInstanceOf(IllegalTransactionStateException.class);
	}

	@Test
	void finishedDeadlinesArePurgedAfterThirtyDays() {
		UUID old = plain(this.t0, "old");
		this.poller.runDue();
		this.clock.advance(Duration.ofDays(29));
		UUID recent = plain(this.clock.instant(), "recent");
		this.poller.runDue();
		UUID waiting = plain(this.clock.instant().plus(Duration.ofDays(60)), "waiting");

		this.clock.advance(Duration.ofDays(1));
		assertThat(this.poller.purge()).isOne();

		assertThat(this.jdbc.sql("SELECT id FROM timers.deadline").query(UUID.class).list())
			.containsExactlyInAnyOrder(recent, waiting)
			.doesNotContain(old);
	}

	private UUID plain(Instant due, String generation) {
		return this.tx.execute(status -> this.deadlines
			.schedule(new DeadlineRequest(ScriptedDeadlineHandler.PLAIN.key(), UUID.randomUUID(), generation, null, due)));
	}

	private UUID pausable(UUID job, Instant due) {
		return this.tx.execute(status -> this.deadlines
			.schedule(new DeadlineRequest(ScriptedDeadlineHandler.PAUSABLE.key(), UUID.randomUUID(), "g", job, due)));
	}

	private Map<String, Object> row(UUID id) {
		return this.jdbc.sql("SELECT * FROM timers.deadline WHERE id = :id").param("id", id).query().singleRow();
	}

}
