package vn.vibefix.shared;

import java.util.List;
import java.util.Optional;

/**
 * FR-62 (SM-C2, SM-9): one module's figures for ADM-9, counts and, since Story 17.2, rates and averages.
 * Each module that owns a figure implements it over its own tables; {@code admin} shows every group. No
 * figure names a person.
 */
public interface PlatformCounts {

	/** How ADM-9 reads a figure's value. */
	enum Unit {

		/** A number of things. */
		COUNT,

		/** A rate in tenths of a percent: 755 reads "75,5 %". */
		PERCENT_TENTHS,

		/** An average out of 5 stars, in tenths: 45 reads "4,5". */
		AVERAGE_TENTHS

	}

	/** One figure: its group (a table on ADM-9), its key within the group, the value, and its unit. */
	record Count(String group, String key, long value, Unit unit) {

		/** A count. */
		public Count(String group, String key, long value) {
			this(group, key, value, Unit.COUNT);
		}

	}

	/**
	 * A rate of {@code part} in {@code whole}, rounded half up to a tenth of a percent; empty for a
	 * {@code whole} of 0, which ADM-9 leaves out (Story 17.2).
	 */
	static Optional<Count> rate(String group, String key, long part, long whole) {
		return whole < 1 ? Optional.empty()
				: Optional.of(new Count(group, key, Math.round(1000.0 * part / whole), Unit.PERCENT_TENTHS));
	}

	List<Count> counts();

}
