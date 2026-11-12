package vn.vibefix.product.internal;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Component;

import vn.vibefix.shared.RateLimitedException;

/**
 * The "Kiểm tra" limit (AD-34 seed limits, Story 23.5c): every check that reads GitHub through the Source App on a
 * Customer's request (CUS-3's repository and workflow checks and each registration attempt, which reads them all again,
 * and CUS-4's "Kiểm tra lại") spends the Source App's shared
 * GitHub quota, so each subject gets {@value #PER_SUBJECT} an hour (a Product, or for a draft the Customer and the
 * repository's full name in lower case) and each Customer {@value #PER_CUSTOMER} an hour across all of them
 * {@code [ASSUMPTION]}. Past either, the check is refused with {@code 429 RATE_LIMITED CHECK_LIMIT} and
 * {@code Retry-After}, and uses up nothing. The windows are kept in memory, as {@code MemorySearchWindow} keeps its
 * own: per application instance, and from scratch after a restart.
 */
@Component
class CheckWindow {

	static final int PER_SUBJECT = 30;

	static final int PER_CUSTOMER = 120;

	static final Duration WINDOW = Duration.ofHours(1);

	/** Windows kept before the idle ones are dropped, so many subjects cannot grow memory without end. */
	static final int MAX_TRACKED = 10_000;

	private final Map<String, Deque<Instant>> windows = new HashMap<>();

	private final Clock clock;

	CheckWindow(Clock clock) {
		this.clock = clock;
	}

	/**
	 * Counts one check of a registered Product by its Customer.
	 * @throws RateLimitedException {@code CHECK_LIMIT} past either limit
	 */
	void product(UUID customerId, UUID productId) {
		count(customerId, "product:" + productId);
	}

	/**
	 * Counts one check of the Customer's draft of {@code fullName}, whatever its case.
	 * @throws RateLimitedException {@code CHECK_LIMIT} past either limit
	 */
	void draft(UUID customerId, String fullName) {
		count(customerId, "draft:" + customerId + ":" + fullName.toLowerCase(Locale.ROOT));
	}

	private synchronized void count(UUID customerId, String subject) {
		Instant now = this.clock.instant();
		if (this.windows.size() > MAX_TRACKED) {
			this.windows.values().removeIf(times -> {
				trim(times, now);
				return times.isEmpty();
			});
		}
		Deque<Instant> own = window(subject, now);
		Deque<Instant> all = window("customer:" + customerId, now);
		Duration wait = Duration.ZERO;
		if (own.size() >= PER_SUBJECT) {
			wait = later(wait, Duration.between(now, own.peekFirst().plus(WINDOW)));
		}
		if (all.size() >= PER_CUSTOMER) {
			wait = later(wait, Duration.between(now, all.peekFirst().plus(WINDOW)));
		}
		if (own.size() >= PER_SUBJECT || all.size() >= PER_CUSTOMER) {
			throw new RateLimitedException(wait, ProductReason.CHECK_LIMIT);
		}
		own.addLast(now);
		all.addLast(now);
	}

	private Deque<Instant> window(String key, Instant now) {
		Deque<Instant> times = this.windows.computeIfAbsent(key, ignored -> new ArrayDeque<>());
		trim(times, now);
		return times;
	}

	/** Drops the checks that left the window. */
	private static void trim(Deque<Instant> times, Instant now) {
		Instant start = now.minus(WINDOW);
		while (!times.isEmpty() && !times.peekFirst().isAfter(start)) {
			times.pollFirst();
		}
	}

	private static Duration later(Duration one, Duration other) {
		return one.compareTo(other) >= 0 ? one : other;
	}

}
