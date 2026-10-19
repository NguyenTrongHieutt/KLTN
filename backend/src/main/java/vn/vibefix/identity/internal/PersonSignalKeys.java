package vn.vibefix.identity.internal;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.core.env.Environment;

/**
 * The person-link signal keys (AD-19, AD-22): 32-byte secret files
 * {@code identity-person-signal-key-{n}} in hexadecimal, the current version computing new signals
 * and every present version answering lookups. A key rotates by adding the next file and raising
 * {@code vibefix.identity.person-signal-key-version}; signals are recorded under every live version,
 * so matches across the rotation still hold.
 */
class PersonSignalKeys {

	static final String KEY_PROPERTY_PREFIX = "identity-person-signal-key-";

	/** The highest key version looked for, as for the token keys. */
	static final int MAX_VERSION = 20;

	private final Map<Integer, SecretKeySpec> keys = new TreeMap<>();

	private final int currentVersion;

	PersonSignalKeys(Environment environment, int currentVersion) {
		this.currentVersion = currentVersion;
		for (int version = 1; version <= MAX_VERSION; version++) {
			String hex = environment.getProperty(KEY_PROPERTY_PREFIX + version);
			if (hex != null && !hex.isBlank()) {
				byte[] key = HexFormat.of().parseHex(hex.strip());
				if (key.length != 32) {
					throw new IllegalStateException(KEY_PROPERTY_PREFIX + version + " must hold 32 bytes in hexadecimal");
				}
				this.keys.put(version, new SecretKeySpec(key, "HmacSHA256"));
			}
		}
		if (!this.keys.containsKey(currentVersion)) {
			throw new IllegalStateException("The person-link signal key " + KEY_PROPERTY_PREFIX + currentVersion
					+ " is missing; it is a secret file (deploy/dev-secrets.sh creates the dev one)");
		}
	}

	int currentVersion() {
		return this.currentVersion;
	}

	/** The signal of {@code value} under the current key. */
	String current(String kind, String value) {
		return hmac(this.keys.get(this.currentVersion), kind, value);
	}

	/** The signal of {@code value} under every live key version, keyed by version. */
	Map<Integer, String> all(String kind, String value) {
		Map<Integer, String> hmacs = new LinkedHashMap<>();
		this.keys.forEach((version, key) -> hmacs.put(version, hmac(key, kind, value)));
		return hmacs;
	}

	private static String hmac(SecretKeySpec key, String kind, String value) {
		try {
			Mac mac = Mac.getInstance("HmacSHA256");
			mac.init(key);
			return HexFormat.of().formatHex(mac.doFinal((kind + ":" + value).getBytes(StandardCharsets.UTF_8)));
		}
		catch (GeneralSecurityException ex) {
			throw new IllegalStateException("Every Java platform has HmacSHA256", ex);
		}
	}

}
