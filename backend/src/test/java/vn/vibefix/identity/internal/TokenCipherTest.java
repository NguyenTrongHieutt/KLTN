package vn.vibefix.identity.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.HexFormat;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

/**
 * AD-22, SG-9: a kept token is AES-GCM encrypted under the versioned keyring, bound to its associated
 * data (the account ID), and an older key still opens what it sealed.
 */
class TokenCipherTest {

	private static final String KEY_1 = "11".repeat(32);

	private static final String KEY_2 = "22".repeat(32);

	@Test
	void sealsAndOpensWithTheSameAssociatedData() {
		TokenCipher cipher = cipher(1, Map.of("identity-token-key-1", KEY_1));

		TokenCipher.Sealed sealed = cipher.seal("gho_secret-token", "account-1");

		assertThat(sealed.keyVersion()).isEqualTo(1);
		assertThat(sealed.nonce()).hasSize(12);
		assertThat(new String(sealed.ciphertext())).doesNotContain("gho_secret-token");
		assertThat(cipher.open(sealed, "account-1")).isEqualTo("gho_secret-token");
	}

	@Test
	void anotherAccountsAssociatedDataOrATamperedCiphertextDoesNotOpen() {
		TokenCipher cipher = cipher(1, Map.of("identity-token-key-1", KEY_1));
		TokenCipher.Sealed sealed = cipher.seal("gho_secret-token", "account-1");

		assertThatThrownBy(() -> cipher.open(sealed, "account-2")).isInstanceOf(IllegalStateException.class);
		byte[] tampered = sealed.ciphertext().clone();
		tampered[0] ^= 1;
		assertThatThrownBy(() -> cipher.open(new TokenCipher.Sealed(1, sealed.nonce(), tampered), "account-1"))
			.isInstanceOf(IllegalStateException.class);
	}

	@Test
	void everySealUsesAFreshNonce() {
		TokenCipher cipher = cipher(1, Map.of("identity-token-key-1", KEY_1));

		assertThat(cipher.seal("t", "a").nonce()).isNotEqualTo(cipher.seal("t", "a").nonce());
	}

	@Test
	void afterRotationNewTokensUseTheCurrentKeyAndOldOnesStillOpen() {
		TokenCipher.Sealed old = cipher(1, Map.of("identity-token-key-1", KEY_1)).seal("gho_old", "a");
		TokenCipher rotated = cipher(2, Map.of("identity-token-key-1", KEY_1, "identity-token-key-2", KEY_2));

		assertThat(rotated.seal("gho_new", "a").keyVersion()).isEqualTo(2);
		assertThat(rotated.open(old, "a")).isEqualTo("gho_old");
	}

	@Test
	void refusesToStartWithoutAUsableCurrentKey() {
		assertThatThrownBy(() -> cipher(1, Map.of())).isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("identity-token-key-1");
		assertThatThrownBy(() -> cipher(1, Map.of("identity-token-key-1", "abcd")))
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("64 hexadecimal");
		assertThat(HexFormat.of().parseHex(KEY_1)).hasSize(32);
	}

	private static TokenCipher cipher(int currentVersion, Map<String, String> keys) {
		MockEnvironment environment = new MockEnvironment();
		keys.forEach(environment::setProperty);
		return new TokenCipher(environment, currentVersion);
	}

}
