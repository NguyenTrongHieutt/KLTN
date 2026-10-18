package vn.vibefix.operations.internal;

import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Runs the {@link OperationWorker} poller every {@code vibefix.operations.poll-interval} (seed: 30 s);
 * off when scheduling is off ({@code vibefix.scheduling.enabled=false}), as in tests.
 */
@Component
@ConditionalOnBooleanProperty(name = "vibefix.scheduling.enabled", matchIfMissing = true)
class OperationScheduler {

	private final OperationWorker worker;

	OperationScheduler(OperationWorker worker) {
		this.worker = worker;
	}

	@Scheduled(fixedDelayString = "${vibefix.operations.poll-interval}",
			initialDelayString = "${vibefix.operations.poll-interval}")
	void poll() {
		this.worker.poll();
	}

}
