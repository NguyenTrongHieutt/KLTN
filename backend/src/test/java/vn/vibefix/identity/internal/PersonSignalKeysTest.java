package vn.vibefix.identity.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

/** AD-19, AD-22: the signal keyring, current version and every live one. */
class PersonSignalKeysTest {

	private static final String KEY_1 = "11".repeat(32);

	private static final String KEY_2 = "22".repeat(32);

	@Test
	void signalsAreComputedUnderEveryLiveKeyAndTheCurrentOneIsNamed() {
		PersonSignalKeys keys = keys(2, Map.of("identity-person-signal-key-1", KEY_1, "identity-person-signal-key-2", KEY_2));

		Map<Integer, String> all = keys.all("EMAIL", "lan@example.test");

		assertThat(all).containsOnlyKeys(1, 2);
		assertThat(all.get(1)).isNotEqualTo(all.get(2)).matches("[0-9a-f]{64}");
		assertThat(keys.current("EMAIL", "lan@example.test")).isEqualTo(all.get(2));
		assertThat(keys.current("LINKEDIN", "lan@example.test")).as("the kind is part of the signal")
			.isNotEqualTo(all.get(2));
	}

	@Test
	void aMissingCurrentKeyOrAShortOneRefusesToStart() {
		assertThatThrownBy(() -> keys(2, Map.of("identity-person-signal-key-1", KEY_1)))
			.hasMessageContaining("identity-person-signal-key-2");
		assertThatThrownBy(() -> keys(1, Map.of("identity-person-signal-key-1", "abcd")))
			.hasMessageContaining("32 bytes");
	}

	private static PersonSignalKeys keys(int current, Map<String, String> files) {
		MockEnvironment environment = new MockEnvironment();
		files.forEach(environment::setProperty);
		return new PersonSignalKeys(environment, current);
	}

}
