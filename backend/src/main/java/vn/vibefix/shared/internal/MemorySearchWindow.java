package vn.vibefix.shared.internal;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Component;

import vn.vibefix.shared.RateLimitedException;
import vn.vibefix.shared.SearchWindow;

/**
 * {@link SearchWindow} as a per-minute window kept in memory, as AD-34 places such limits: one window per account,
 * whichever search it runs.
 */
@Component
class MemorySearchWindow implements SearchWindow {

	private final Map<UUID, Deque<Instant>> searches = new ConcurrentHashMap<>();

	private final Clock clock;

	MemorySearchWindow(Clock clock) {
		this.clock = clock;
	}

	@Override
	public void record(UUID accountId) {
		Instant now = this.clock.instant();
		Deque<Instant> times = this.searches.computeIfAbsent(accountId, id -> new ArrayDeque<>());
		synchronized (times) {
			Instant start = now.minus(WINDOW);
			while (!times.isEmpty() && !times.peekFirst().isAfter(start)) {
				times.pollFirst();
			}
			if (times.size() >= LIMIT) {
				throw new RateLimitedException(Duration.between(now, times.peekFirst().plus(WINDOW)));
			}
			times.addLast(now);
		}
	}

}
