package vn.vibefix.timers.internal;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import vn.vibefix.timers.DeadlineHandler;
import vn.vibefix.timers.DeadlineOutcome;
import vn.vibefix.timers.DueDeadline;

/**
 * Runs due Deadlines (AD-7): each in its own transaction, claimed with {@code FOR UPDATE SKIP LOCKED}
 * so two pollers never run one twice, with its handler's effects and its outcome committed together.
 * A handler that throws rolls both back; once the rollback has released the row, the Deadline is
 * pushed one poll interval later, and the failure is logged.
 */
@Component
@EnableConfigurationProperties(TimersProperties.class)
public class DeadlinePoller {

	/** How long a finished Deadline is kept. */
	static final Duration KEPT = Duration.ofDays(30);

	private static final Logger log = LoggerFactory.getLogger(DeadlinePoller.class);

	private final JdbcDeadlines store;

	private final DeadlineHandlers handlers;

	private final TimersProperties properties;

	private final TransactionTemplate transactions;

	private final Clock clock;

	DeadlinePoller(JdbcDeadlines store, DeadlineHandlers handlers, TimersProperties properties,
			PlatformTransactionManager transactionManager, Clock clock) {
		this.store = store;
		this.handlers = handlers;
		this.properties = properties;
		this.transactions = new TransactionTemplate(transactionManager);
		this.clock = clock;
	}

	/**
	 * Runs up to a batch of due Deadlines, earliest first.
	 * @return how many were claimed, failed ones included
	 */
	public int runDue() {
		int claimed = 0;
		while (claimed < this.properties.batchSize()) {
			Instant now = this.clock.instant();
			AtomicReference<DueDeadline> current = new AtomicReference<>();
			Optional<DueDeadline> ran;
			try {
				ran = this.transactions.execute(status -> runOne(now, current));
			}
			catch (RuntimeException ex) {
				DueDeadline failed = current.get();
				if (failed == null) {
					throw ex;
				}
				log.error("Deadline {} of kind {} failed; it is due again in {}", failed.id(), failed.kind(),
						this.properties.pollInterval(), ex);
				this.transactions.executeWithoutResult(
						status -> this.store.dueAgain(failed.id(), now.plus(this.properties.pollInterval())));
				claimed++;
				continue;
			}
			if (ran == null || ran.isEmpty()) {
				break;
			}
			claimed++;
		}
		return claimed;
	}

	/** Deletes Deadlines finished 30 days ago or more; @return how many */
	public int purge() {
		Integer purged = this.transactions.execute(status -> this.store.purgeFinished(this.clock.instant(), KEPT));
		return purged == null ? 0 : purged;
	}

	private Optional<DueDeadline> runOne(Instant now, AtomicReference<DueDeadline> current) {
		Optional<DueDeadline> due = this.store.claimDue(now);
		due.ifPresent(deadline -> {
			current.set(deadline);
			apply(deadline, handle(deadline), now);
		});
		return due;
	}

	private DeadlineOutcome handle(DueDeadline deadline) {
		DeadlineHandler handler = this.handlers.require(deadline.kind());
		DeadlineOutcome outcome = handler.handle(deadline);
		if (outcome == null) {
			throw new IllegalStateException("The handler of " + deadline.kind() + " answered no outcome");
		}
		if (outcome == DeadlineOutcome.REPAUSE && !handler.kind().pausable()) {
			throw new IllegalStateException("Deadline kind " + deadline.kind() + " is not pausable, so it cannot REPAUSE");
		}
		return outcome;
	}

	private void apply(DueDeadline deadline, DeadlineOutcome outcome, Instant now) {
		switch (outcome) {
			case FIRED, OBSOLETE -> this.store.finish(deadline.id(), outcome.name(), now);
			case REARM -> this.store.dueAgain(deadline.id(), now.plus(this.properties.pollInterval()));
			case REPAUSE -> this.store.repause(deadline);
		}
	}

}
