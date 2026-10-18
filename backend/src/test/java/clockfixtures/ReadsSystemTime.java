package clockfixtures;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;

/**
 * Deliberately breaks the clock rule, outside {@code vn.vibefix} so that only {@code ClockRuleTest}
 * imports it, to prove the rule fires.
 */
public class ReadsSystemTime {

	Instant instant() {
		return Instant.now();
	}

	LocalDateTime localDateTime() {
		return LocalDateTime.now();
	}

	ZonedDateTime zoned() {
		return ZonedDateTime.now(ZoneOffset.UTC);
	}

	Clock clock() {
		return Clock.systemUTC();
	}

	LocalDate allowed(Clock clock) {
		return LocalDate.now(clock);
	}

}
