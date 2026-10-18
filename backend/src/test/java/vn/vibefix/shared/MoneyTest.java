package vn.vibefix.shared;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/** Conventions, Money: whole VND as {@code long}, never floating point, never negative. */
class MoneyTest {

	@Test
	void holdsWholeDong() {
		Money price = Money.ofVnd(1_500_000);

		assertThat(price.vnd()).isEqualTo(1_500_000L);
		assertThat(price).hasToString("1500000 VND");
		assertThat(Money.ZERO.isZero()).isTrue();
	}

	@Test
	void addsAndSubtracts() {
		Money funded = Money.ofVnd(2_000_000);
		Money released = Money.ofVnd(1_250_000);

		assertThat(funded.plus(released)).isEqualTo(Money.ofVnd(3_250_000));
		assertThat(funded.minus(released)).isEqualTo(Money.ofVnd(750_000));
		assertThat(funded.minus(funded)).isEqualTo(Money.ZERO);
	}

	@Test
	void comparesByAmount() {
		assertThat(Money.ofVnd(10).isGreaterThan(Money.ofVnd(9))).isTrue();
		assertThat(Money.ofVnd(9).isGreaterThan(Money.ofVnd(9))).isFalse();
		assertThat(Money.ofVnd(9)).isLessThan(Money.ofVnd(10));
		assertThat(Money.ofVnd(5)).isEqualTo(new Money(5)).hasSameHashCodeAs(new Money(5));
	}

	@Test
	void isNeverNegative() {
		assertThatThrownBy(() -> Money.ofVnd(-1)).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> Money.ofVnd(1).minus(Money.ofVnd(2))).isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void refusesToOverflow() {
		assertThatThrownBy(() -> Money.ofVnd(Long.MAX_VALUE).plus(Money.ofVnd(1)))
			.isInstanceOf(ArithmeticException.class);
	}

}
