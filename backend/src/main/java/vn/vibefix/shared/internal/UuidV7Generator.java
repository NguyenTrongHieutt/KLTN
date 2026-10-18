package vn.vibefix.shared.internal;

import java.security.SecureRandom;
import java.time.Clock;
import java.util.UUID;

import org.springframework.stereotype.Component;

import vn.vibefix.shared.IdGenerator;

/**
 * RFC 9562 version 7: 48 bits of Unix milliseconds from the injected {@link Clock}, then 74 random
 * bits around the version and variant fields. Random bits make IDs from one millisecond distinct
 * without shared state; their order within the millisecond is arbitrary.
 */
@Component
class UuidV7Generator implements IdGenerator {

	private final Clock clock;

	private final SecureRandom random = new SecureRandom();

	UuidV7Generator(Clock clock) {
		this.clock = clock;
	}

	@Override
	public UUID newId() {
		long millis = this.clock.millis();
		long mostSignificant = (millis << 16) | 0x7000L | (this.random.nextLong() & 0x0FFFL);
		long leastSignificant = (this.random.nextLong() & 0x3FFF_FFFF_FFFF_FFFFL) | 0x8000_0000_0000_0000L;
		return new UUID(mostSignificant, leastSignificant);
	}

}
