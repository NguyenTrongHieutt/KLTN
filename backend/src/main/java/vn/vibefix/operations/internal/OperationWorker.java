package vn.vibefix.operations.internal;

import java.time.Clock;
import java.time.Instant;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import vn.vibefix.operations.Lane;
import vn.vibefix.operations.OperationHandler;
import vn.vibefix.operations.OperationFailed;
import vn.vibefix.operations.OperationPolicy;
import vn.vibefix.operations.OperationStatus;
import vn.vibefix.operations.Outcome;
import vn.vibefix.operations.internal.OperationStore.Claimed;
import vn.vibefix.operations.internal.OperationsProperties.LaneSettings;
import vn.vibefix.shared.IdGenerator;

/**
 * Runs claimed operations outside any transaction (AD-6). The poller ({@link #poll}) claims what each
 * lane has room for and runs it on that lane's threads; tests run a lane's due work on their own
 * thread with {@link #runDue}.
 * <p>
 * One attempt: read back first when the previous attempt was ambiguous or lost its lease; run the
 * handler; then complete, retry later (30 s doubling by default), or defer without counting the
 * attempt. After the last attempt a final read-back decides between {@code SUCCEEDED} and
 * {@code FAILED}.
 */
@Component
@EnableConfigurationProperties(OperationsProperties.class)
public class OperationWorker implements DisposableBean {

	private static final Logger log = LoggerFactory.getLogger(OperationWorker.class);

	/** The MDC key of {@code shared.internal.CorrelationIdFilter}: an attempt's log lines carry the operation ID. */
	static final String CORRELATION_ID = "correlationId";

	private static final int MAX_ROUNDS = 1_000;

	private final OperationStore store;

	private final OperationHandlers handlers;

	private final OperationsProperties properties;

	private final Clock clock;

	private final Map<Lane, LaneThreads> lanes = new EnumMap<>(Lane.class);

	private final ApplicationEventPublisher events;

	private final TransactionTemplate transactions;

	private final IdGenerator ids;

	OperationWorker(OperationStore store, OperationHandlers handlers, OperationsProperties properties, Clock clock,
			ApplicationEventPublisher events, PlatformTransactionManager transactionManager, IdGenerator ids) {
		this.events = events;
		this.transactions = new TransactionTemplate(transactionManager);
		this.ids = ids;
		this.store = store;
		this.handlers = handlers;
		this.properties = properties;
		this.clock = clock;
		for (Lane lane : handlers.lanes()) {
			this.lanes.put(lane, new LaneThreads(lane, properties.lane(lane).concurrency()));
		}
	}

	/** Claims, for every lane with handlers, as many due operations as it has free threads, and starts them. */
	void poll() {
		this.lanes.forEach((lane, threads) -> {
			if (threads.executor.isShutdown()) {
				// Shutting down: claiming now would leave the rows running until their leases expire.
				return;
			}
			int free = threads.permits.availablePermits();
			for (Claimed claimed : claim(lane, free)) {
				threads.permits.acquireUninterruptibly();
				threads.executor.execute(() -> {
					try {
						execute(claimed);
					}
					finally {
						threads.permits.release();
					}
				});
			}
		});
	}

	/**
	 * Runs every due operation of {@code lane} on the calling thread, a lane's worth at a time, until
	 * none is due at the clock's current time.
	 * @return how many attempts ran
	 */
	public int runDue(Lane lane) {
		int attempts = 0;
		for (int round = 0; round < MAX_ROUNDS; round++) {
			List<Claimed> claimed = claim(lane, this.properties.lane(lane).concurrency());
			if (claimed.isEmpty()) {
				return attempts;
			}
			for (Claimed one : claimed) {
				execute(one);
				attempts++;
			}
		}
		throw new IllegalStateException("Lane " + lane + " still has due operations after " + MAX_ROUNDS + " rounds");
	}

	private List<Claimed> claim(Lane lane, int limit) {
		LaneSettings settings = this.properties.lane(lane);
		Instant now = this.clock.instant();
		return this.store.claim(lane, this.handlers.types(lane), limit, now, now.plus(settings.timeout()));
	}

	void execute(Claimed claimed) {
		String previous = MDC.get(CORRELATION_ID);
		MDC.put(CORRELATION_ID, claimed.id().toString());
		try {
			attempt(claimed, this.handlers.require(claimed.type()));
		}
		finally {
			if (previous == null) {
				MDC.remove(CORRELATION_ID);
			}
			else {
				MDC.put(CORRELATION_ID, previous);
			}
		}
	}

	private void attempt(Claimed claimed, OperationHandler handler) {
		OperationPolicy policy = handler.policy();
		if (claimed.readBackFirst()) {
			Optional<Map<String, Object>> done = readBack(handler, claimed);
			if (done.isPresent()) {
				complete(claimed, OperationStatus.SUCCEEDED, done.get(), null);
				return;
			}
			if (claimed.attempt() > policy.maxAttempts()) {
				// The last attempt lost its lease and left no effect behind.
				fail(claimed, claimed.lastErrorCode() == null ? "LEASE_EXPIRED" : claimed.lastErrorCode());
				return;
			}
		}
		switch (run(handler, claimed)) {
			case Outcome.Succeeded succeeded -> complete(claimed, OperationStatus.SUCCEEDED, succeeded.result(), null);
			case Outcome.Obsolete obsolete -> complete(claimed, OperationStatus.OBSOLETE, null, obsolete.code());
			case Outcome.Aborted aborted -> complete(claimed, OperationStatus.ABORTED, null, aborted.code());
			case Outcome.Deferred deferred -> this.store.defer(claimed, deferred.until());
			case Outcome.Transient failed -> failed(handler, policy, claimed, failed.code(), false);
			case Outcome.Ambiguous unknown -> failed(handler, policy, claimed, unknown.code(), true);
		}
	}

	private void failed(OperationHandler handler, OperationPolicy policy, Claimed claimed, String code,
			boolean ambiguous) {
		if (claimed.attempt() < policy.maxAttempts()) {
			Instant next = this.clock.instant().plus(policy.delayAfter(claimed.attempt()));
			this.store.retryLater(claimed, next, code, ambiguous);
			log.info("Operation {} attempt {} failed with {}; next attempt at {}", claimed.type(), claimed.attempt(),
					code, next);
			return;
		}
		Optional<Map<String, Object>> done = readBack(handler, claimed);
		if (done.isPresent()) {
			complete(claimed, OperationStatus.SUCCEEDED, done.get(), null);
			return;
		}
		fail(claimed, code);
	}

	private void fail(Claimed claimed, String code) {
		if (complete(claimed, OperationStatus.FAILED, null, code)) {
			log.warn("Operation {} failed after {} attempts with {}; it waits in the Administrator queue",
					claimed.type(), claimed.attempt(), code);
			// AD-6: every Administrator is alerted; the event's own transaction records its publication.
			this.transactions.executeWithoutResult(status -> this.events
				.publishEvent(new OperationFailed(this.ids.newId(), claimed.id(), claimed.type(), code)));
		}
	}

	private boolean complete(Claimed claimed, OperationStatus status, @Nullable Map<String, Object> result,
			@Nullable String code) {
		boolean completed = this.store.complete(claimed, status, result, code, this.clock.instant());
		if (!completed) {
			log.info("Operation {} attempt {} lost its lease; another attempt holds it", claimed.type(),
					claimed.attempt());
		}
		return completed;
	}

	/** The handler's outcome; an exception is a transient failure, logged without its message (it may name a person). */
	private static Outcome run(OperationHandler handler, Claimed claimed) {
		try {
			return handler.run(claimed.run());
		}
		catch (RuntimeException ex) {
			log.error("Operation {} attempt {} threw {}", claimed.type(), claimed.attempt(), ex.getClass().getName());
			return new Outcome.Transient("UNEXPECTED");
		}
	}

	private static Optional<Map<String, Object>> readBack(OperationHandler handler, Claimed claimed) {
		try {
			return handler.readBack(claimed.run());
		}
		catch (RuntimeException ex) {
			log.error("Operation {} read-back threw {}", claimed.type(), ex.getClass().getName());
			return Optional.empty();
		}
	}

	@Override
	public void destroy() throws InterruptedException {
		for (Map.Entry<Lane, LaneThreads> entry : this.lanes.entrySet()) {
			ExecutorService executor = entry.getValue().executor;
			executor.shutdown();
			// An attempt cut off here keeps its lease; it is claimed again, reading back first.
			executor.awaitTermination(this.properties.lane(entry.getKey()).timeout().toMillis(), TimeUnit.MILLISECONDS);
		}
	}

	/** A lane's threads and the permits that keep at most its concurrency running. */
	private static final class LaneThreads {

		final ExecutorService executor;

		final Semaphore permits;

		LaneThreads(Lane lane, int concurrency) {
			this.executor = Executors.newFixedThreadPool(concurrency,
					Thread.ofPlatform().name("operations-" + lane.name().toLowerCase().replace('_', '-') + "-", 1).factory());
			this.permits = new Semaphore(concurrency);
		}

	}

}
