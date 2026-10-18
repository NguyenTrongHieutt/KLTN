package vn.vibefix.shared;

/**
 * An amount of money in whole Vietnamese dong, never floating point (Conventions, Money).
 * Amounts are never negative: a refund or a price decrease is its own positive amount. API fields
 * carrying money are plain {@code long} values whose names end in {@code Vnd}.
 *
 * @param vnd the amount in whole VND, zero or more
 */
public record Money(long vnd) implements Comparable<Money> {

	public static final Money ZERO = new Money(0);

	public Money {
		if (vnd < 0) {
			throw new IllegalArgumentException("Money cannot be negative: " + vnd);
		}
	}

	public static Money ofVnd(long vnd) {
		return new Money(vnd);
	}

	/** @throws ArithmeticException if the sum overflows {@code long} */
	public Money plus(Money other) {
		return new Money(Math.addExact(this.vnd, other.vnd));
	}

	/** @throws IllegalArgumentException if {@code other} is larger than this amount */
	public Money minus(Money other) {
		return new Money(this.vnd - other.vnd);
	}

	public boolean isZero() {
		return this.vnd == 0;
	}

	public boolean isGreaterThan(Money other) {
		return this.vnd > other.vnd;
	}

	@Override
	public int compareTo(Money other) {
		return Long.compare(this.vnd, other.vnd);
	}

	@Override
	public String toString() {
		return this.vnd + " VND";
	}

}
