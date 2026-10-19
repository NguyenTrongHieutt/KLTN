package vn.vibefix.shared;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

/** AD-33's one split: floors per party, the remainder to the Mentor, no zero parts. */
class CrewSplitTest {

	private static final UUID MENTOR = UUID.fromString("00000000-0000-0000-0000-00000000000a");

	private static final UUID VY = UUID.fromString("00000000-0000-0000-0000-00000000000b");

	private static final UUID MINH = UUID.fromString("00000000-0000-0000-0000-00000000000c");

	@Test
	void theFramesExampleSplitsExactly() {
		Map<UUID, Long> parts = CrewSplit.split(2_000_000, shares(20, 30, 50), MENTOR);

		assertThat(parts).containsExactly(Map.entry(MENTOR, 400_000L), Map.entry(VY, 600_000L), Map.entry(MINH, 1_000_000L));
	}

	@Test
	void theRemainderBelowOneDongGoesToTheMentor() {
		Map<UUID, Long> parts = CrewSplit.split(1001, shares(20, 30, 50), MENTOR);

		assertThat(parts.get(VY)).isEqualTo(300);
		assertThat(parts.get(MINH)).isEqualTo(500);
		assertThat(parts.get(MENTOR)).isEqualTo(201);
		assertThat(parts.values().stream().mapToLong(Long::longValue).sum()).isEqualTo(1001);
	}

	@Test
	void aZeroPartIsLeftOut() {
		assertThat(CrewSplit.split(500_000, shares(0, 40, 60), MENTOR)).doesNotContainKey(MENTOR);
		assertThat(CrewSplit.split(1, shares(20, 30, 50), MENTOR)).containsOnlyKeys(MENTOR);
	}

	@Test
	void sharesMustSumToOneHundredAndNameTheMentor() {
		assertThatThrownBy(() -> CrewSplit.split(100, shares(20, 30, 40), MENTOR)).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> CrewSplit.split(100, Map.of(VY, 100), MENTOR)).isInstanceOf(IllegalArgumentException.class);
	}

	private static Map<UUID, Integer> shares(int mentor, int vy, int minh) {
		Map<UUID, Integer> shares = new LinkedHashMap<>();
		shares.put(MENTOR, mentor);
		shares.put(VY, vy);
		shares.put(MINH, minh);
		return shares;
	}

}
