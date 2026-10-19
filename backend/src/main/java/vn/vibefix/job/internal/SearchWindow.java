package vn.vibefix.job.internal;

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

/**
 * At most {@value #LIMIT} keyword searches of Job Posts per account in any minute (NFR-3, AD-34): a
 * per-minute window kept in memory, as AD-34 places such limits. Browsing by filter alone is no search.
 */
@Component
class SearchWindow {

	static final int LIMIT = 60;

	static final Duration WINDOW = Duration.ofMinutes(1);

	private final Map<UUID, Deque<Instant>> searches = new ConcurrentHashMap<>();

	private final Clock clock;

	SearchWindow(Clock clock) {
		this.clock = clock;
	}

	/**
	 * Counts one search by the account.
	 * @throws RateLimitedException when it would be the 61st in a minute, retried once the oldest
	 * counted search leaves the window
	 */
	void record(UUID accountId) {
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
