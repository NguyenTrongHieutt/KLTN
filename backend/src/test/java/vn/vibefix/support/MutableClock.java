package vn.vibefix.support;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

/**
 * A fixed {@link Clock} that tests can set or advance (Conventions, Testing). Integration tests get
 * it as the primary {@code Clock} bean, reset to {@link #DEFAULT_INSTANT} before each test.
 */
public final class MutableClock extends Clock {

	public static final Instant DEFAULT_INSTANT = Instant.parse("2026-01-05T03:00:00Z");

	private volatile Instant instant;

	private final ZoneId zone;

	public MutableClock(Instant instant) {
		this(instant, ZoneOffset.UTC);
	}

	private MutableClock(Instant instant, ZoneId zone) {
		this.instant = instant;
		this.zone = zone;
	}

	public void setInstant(Instant instant) {
		this.instant = instant;
	}

	public void advance(Duration duration) {
		this.instant = this.instant.plus(duration);
	}

	public void reset() {
		this.instant = DEFAULT_INSTANT;
	}

	@Override
	public Instant instant() {
		return this.instant;
	}

	@Override
	public ZoneId getZone() {
		return this.zone;
	}

	@Override
	public Clock withZone(ZoneId zone) {
		return Clock.fixed(this.instant, zone);
	}

}
