package vn.vibefix.github.internal;

import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Component;

import vn.vibefix.shared.RateLimitedException;

/**
 * The Actions exchange's abuse limits (AD-34), in memory, so per application instance and from scratch after a
 * restart: {@value #PER_ADDRESS} calls a minute per client address, {@value #IN_ALL} a minute in all, and
 * {@value #PER_CORRELATION} attempts with a verified ID token per correlation within {@link #CORRELATION_WINDOW},
 * the longest a correlation stays open. The address is the connection's remote address until Caddy is in front
 * (Story 18.4). A call over a limit is refused with {@code 429 RATE_LIMITED} and {@code Retry-After}.
 */
@Component
class ExchangeRateLimiter {

	static final int PER_ADDRESS = 10;

	static final int IN_ALL = 60;

	static final int PER_CORRELATION = 5;

	/** A correlation's attempts are counted over its whole open life. */
	static final Duration CORRELATION_WINDOW = GitHubActionsRuns.CORRELATION_LIFETIME;

	/** Entries kept before stale ones are dropped, so a flood of addresses or IDs cannot grow memory without end. */
	static final int MAX_TRACKED = 10_000;

	private static final long MINUTE = 60_000;

	private record Window(long start, int count) {
	}

	private final Map<String, Window> addresses = new ConcurrentHashMap<>();

	private final Map<String, Window> correlations = new ConcurrentHashMap<>();

	private final Object everyone = new Object();

	private Window inAll = new Window(0, 0);

	private final Clock clock;

	ExchangeRateLimiter(Clock clock) {
		this.clock = clock;
	}

	/**
	 * Counts one call from {@code address} against the per-address limit and, once it passes that, the overall
	 * one: calls refused for their address use up nothing of the overall limit, so one address cannot starve
	 * every run.
	 * @throws RateLimitedException over either
	 */
	void call(String address) {
		long now = this.clock.millis();
		long minute = now - now % MINUTE;
		Duration untilNextMinute = Duration.ofMillis(minute + MINUTE - now);
		if (count(this.addresses, address, minute, MINUTE, now) > PER_ADDRESS) {
			throw new RateLimitedException(untilNextMinute);
		}
		synchronized (this.everyone) {
			this.inAll = this.inAll.start() == minute ? new Window(minute, this.inAll.count() + 1) : new Window(minute, 1);
			if (this.inAll.count() > IN_ALL) {
				throw new RateLimitedException(untilNextMinute);
			}
		}
	}

	/**
	 * Counts one attempt on {@code correlation}, as the body names it.
	 * @throws RateLimitedException past {@value #PER_CORRELATION} attempts
	 */
	void attempt(String correlation) {
		long now = this.clock.millis();
		Window window = this.correlations.get(correlation);
		long start = window != null && now - window.start() < CORRELATION_WINDOW.toMillis() ? window.start() : now;
		if (count(this.correlations, correlation, start, CORRELATION_WINDOW.toMillis(), now) > PER_CORRELATION) {
			throw new RateLimitedException(Duration.ofMillis(start + CORRELATION_WINDOW.toMillis() - now));
		}
	}

	/** Every count starts again; for tests that share one clock instant. */
	void reset() {
		this.addresses.clear();
		this.correlations.clear();
		synchronized (this.everyone) {
			this.inAll = new Window(0, 0);
		}
	}

	private static int count(Map<String, Window> windows, String key, long start, long length, long now) {
		if (windows.size() > MAX_TRACKED) {
			windows.values().removeIf(window -> now - window.start() >= length);
		}
		return windows.merge(key, new Window(start, 1),
				(old, fresh) -> old.start() == start ? new Window(start, old.count() + 1) : fresh)
			.count();
	}

}
