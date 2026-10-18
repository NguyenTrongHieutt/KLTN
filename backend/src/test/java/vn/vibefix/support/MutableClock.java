package vn.vibefix.support;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicReference;

/**
 * A fixed {@link Clock} that tests can set or advance (Conventions, Testing). Integration tests get
 * it as the primary {@code Clock} bean, reset to {@link #DEFAULT_INSTANT} before each test.
 * <p>
 * {@link #withZone} returns a view over the same instant, so a component that keeps a zoned clock
 * (for example {@code Asia/Ho_Chi_Minh} for date-only fields) still sees {@link #advance} and
 * {@link #setInstant}.
 */
public final class MutableClock extends Clock {

	public static final Instant DEFAULT_INSTANT = Instant.parse("2026-01-05T03:00:00Z");

	private final AtomicReference<Instant> instant;

	private final ZoneId zone;

	public MutableClock(Instant instant) {
		this(new AtomicReference<>(instant), ZoneOffset.UTC);
	}

	private MutableClock(AtomicReference<Instant> instant, ZoneId zone) {
		this.instant = instant;
		this.zone = zone;
	}

	public void setInstant(Instant instant) {
		this.instant.set(instant);
	}

	public void advance(Duration duration) {
		this.instant.updateAndGet(current -> current.plus(duration));
	}

	public void reset() {
		this.instant.set(DEFAULT_INSTANT);
	}

	@Override
	public Instant instant() {
		return this.instant.get();
	}

	@Override
	public ZoneId getZone() {
		return this.zone;
	}

	@Override
	public MutableClock withZone(ZoneId zone) {
		return new MutableClock(this.instant, zone);
	}

}
