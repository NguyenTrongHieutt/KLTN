package vn.vibefix.identity.internal;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Map;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.core.env.Environment;

/**
 * The versioned keyring for stored user secrets (AD-22): AES-256-GCM with a random 96-bit nonce and
 * the caller's associated data (for a Customer token, the account ID), so a ciphertext cannot be
 * moved to another account. Keys are 32-byte secret files {@code identity-token-key-{n}} in
 * hexadecimal; the current version seals, and every present version opens what it sealed, so a
 * key rotates by adding the next file and raising {@code vibefix.identity.token-key-version}.
 */
class TokenCipher {

	static final String KEY_PROPERTY_PREFIX = "identity-token-key-";

	/** The highest key version looked for; rotations are rare, and old keys are retired by hand. */
	static final int MAX_VERSION = 20;

	private static final int NONCE_BYTES = 12;

	private static final int TAG_BITS = 128;

	/** A sealed value: the key version that sealed it, its nonce, and the ciphertext with its tag. */
	record Sealed(int keyVersion, byte[] nonce, byte[] ciphertext) implements java.io.Serializable {
	}

	private final Map<Integer, SecretKey> keys = new HashMap<>();

	private final int currentVersion;

	private final SecureRandom random = new SecureRandom();

	TokenCipher(Environment environment, int currentVersion) {
		this.currentVersion = currentVersion;
		for (int version = 1; version <= MAX_VERSION; version++) {
			String hex = environment.getProperty(KEY_PROPERTY_PREFIX + version);
			if (hex != null && !hex.isBlank()) {
				this.keys.put(version, key(version, hex.strip()));
			}
		}
		if (!this.keys.containsKey(currentVersion)) {
			throw new IllegalStateException("The token key " + KEY_PROPERTY_PREFIX + currentVersion
					+ " is missing; it is a secret file (deploy/dev-secrets.sh creates the dev one)");
		}
	}

	Sealed seal(String plaintext, String associatedData) {
		byte[] nonce = new byte[NONCE_BYTES];
		this.random.nextBytes(nonce);
		try {
			Cipher cipher = cipher(Cipher.ENCRYPT_MODE, this.keys.get(this.currentVersion), nonce, associatedData);
			return new Sealed(this.currentVersion, nonce, cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8)));
		}
		catch (GeneralSecurityException ex) {
			throw new IllegalStateException("Could not seal a token", ex);
		}
	}

	String open(Sealed sealed, String associatedData) {
		SecretKey key = this.keys.get(sealed.keyVersion());
		if (key == null) {
			throw new IllegalStateException("No token key of version " + sealed.keyVersion());
		}
		try {
			Cipher cipher = cipher(Cipher.DECRYPT_MODE, key, sealed.nonce(), associatedData);
			return new String(cipher.doFinal(sealed.ciphertext()), StandardCharsets.UTF_8);
		}
		catch (GeneralSecurityException ex) {
			throw new IllegalStateException("A sealed token does not open with its associated data", ex);
		}
	}

	private static Cipher cipher(int mode, SecretKey key, byte[] nonce, String associatedData)
			throws GeneralSecurityException {
		Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
		cipher.init(mode, key, new GCMParameterSpec(TAG_BITS, nonce));
		cipher.updateAAD(associatedData.getBytes(StandardCharsets.UTF_8));
		return cipher;
	}

	private static SecretKey key(int version, String hex) {
		if (!hex.matches("[0-9a-fA-F]{64}")) {
			throw new IllegalStateException(
					KEY_PROPERTY_PREFIX + version + " must be 32 bytes written as 64 hexadecimal characters");
		}
		return new SecretKeySpec(HexFormat.of().parseHex(hex), "AES");
	}

}
