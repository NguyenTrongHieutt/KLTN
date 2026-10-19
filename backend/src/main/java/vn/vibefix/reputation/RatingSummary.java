package vn.vibefix.reputation;

import java.math.BigDecimal;

import org.jspecify.annotations.Nullable;

/**
 * An account's visible Ratings (AD-33): their average, to one decimal, and their number.
 * @param average {@code null} exactly when there is no visible Rating
 */
public record RatingSummary(@Nullable BigDecimal average, int count) {

	/** No visible Rating. */
	public static final RatingSummary NONE = new RatingSummary(null, 0);

	public RatingSummary {
		if ((average == null) != (count == 0)) {
			throw new IllegalArgumentException("An average comes with at least one Rating");
		}
	}

}
