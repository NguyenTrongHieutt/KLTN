package eventfixtures;

import java.util.concurrent.atomic.AtomicInteger;

import org.springframework.modulith.events.ApplicationModuleListener;

import vn.vibefix.shared.ProcessedEvents;

/**
 * A replay-safe listener that fails on purpose (AD-9). It marks the event processed before it may
 * fail, so a failed attempt must roll the marker back for the retry to apply the event. Outside
 * {@code vn.vibefix}, so only the tests that import it load it; its counters are static so a test
 * can steer it across application restarts.
 */
public class ProbeListener {

	public static final String NAME = "eventfixtures.ProbeListener.on";

	/** How many more attempts fail. */
	public static final AtomicInteger failuresLeft = new AtomicInteger();

	/** How many times an event was applied. */
	public static final AtomicInteger applied = new AtomicInteger();

	/** How many times the listener ran, failed attempts included. */
	public static final AtomicInteger attempts = new AtomicInteger();

	private final ProcessedEvents processed;

	ProbeListener(ProcessedEvents processed) {
		this.processed = processed;
	}

	public static void reset(int failures) {
		failuresLeft.set(failures);
		applied.set(0);
		attempts.set(0);
	}

	@ApplicationModuleListener
	void on(ProbeEvent event) {
		attempts.incrementAndGet();
		boolean first = this.processed.markProcessed(NAME, event.eventId());
		if (failuresLeft.getAndDecrement() > 0) {
			throw new IllegalStateException("Probe failure on purpose");
		}
		if (first) {
			applied.incrementAndGet();
		}
	}

}
