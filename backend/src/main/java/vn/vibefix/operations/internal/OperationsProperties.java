package vn.vibefix.operations.internal;

import java.time.Duration;
import java.util.EnumMap;
import java.util.Map;

import org.springframework.boot.context.properties.ConfigurationProperties;

import vn.vibefix.operations.Lane;

/**
 * {@code vibefix.operations.*}: the seed operation policy (spine Conventions).
 * @param pollInterval how often the pollers claim due operations (seed: 30 s)
 * @param lanes for every lane, how many operations run at once and the lease of one attempt
 */
@ConfigurationProperties("vibefix.operations")
record OperationsProperties(Duration pollInterval, Map<Lane, LaneSettings> lanes) {

	/**
	 * @param concurrency attempts running at the same time
	 * @param timeout how long one attempt may take; its lease, after which another worker re-claims it
	 */
	record LaneSettings(int concurrency, Duration timeout) {

		LaneSettings {
			if (concurrency < 1) {
				throw new IllegalArgumentException("A lane runs at least one operation at a time");
			}
			if (timeout == null || timeout.isNegative() || timeout.isZero()) {
				throw new IllegalArgumentException("A lane's timeout must be positive");
			}
		}

	}

	OperationsProperties {
		lanes = lanes == null ? Map.of() : new EnumMap<>(lanes);
		for (Lane lane : Lane.values()) {
			if (!lanes.containsKey(lane)) {
				throw new IllegalArgumentException("vibefix.operations.lanes has no settings for " + lane);
			}
		}
	}

	LaneSettings lane(Lane lane) {
		return this.lanes.get(lane);
	}

}
