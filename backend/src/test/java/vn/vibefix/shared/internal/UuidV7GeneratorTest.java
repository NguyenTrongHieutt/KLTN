package vn.vibefix.shared.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import vn.vibefix.support.MutableClock;

/** Conventions, IDs: UUIDv7 primary keys, generated in the application from the injected clock. */
class UuidV7GeneratorTest {

	private final MutableClock clock = new MutableClock(Instant.parse("2026-10-19T02:03:04.567Z"));

	private final UuidV7Generator generator = new UuidV7Generator(this.clock);

	@Test
	void isAVersion7RfcVariantUuid() {
		UUID id = this.generator.newId();

		assertThat(id.version()).isEqualTo(7);
		assertThat(id.variant()).isEqualTo(2);
	}

	@Test
	void startsWithTheClockMillisecond() {
		UUID id = this.generator.newId();

		assertThat(id.getMostSignificantBits() >>> 16).isEqualTo(this.clock.millis());
	}

	@Test
	void idsOfLaterMillisecondsSortAfterEarlierOnes() {
		UUID first = this.generator.newId();
		this.clock.advance(Duration.ofMillis(1));
		UUID second = this.generator.newId();

		assertThat(second.toString()).isGreaterThan(first.toString());
	}

	@Test
	void idsInTheSameMillisecondAreDistinct() {
		Set<UUID> ids = new HashSet<>();
		for (int i = 0; i < 10_000; i++) {
			ids.add(this.generator.newId());
		}

		assertThat(ids).hasSize(10_000);
	}

}
