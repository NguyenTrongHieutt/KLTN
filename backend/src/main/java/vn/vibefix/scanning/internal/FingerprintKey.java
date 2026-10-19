package vn.vibefix.scanning.internal;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.HexFormat;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * The Finding fingerprint key (AD-18, AD-22): the 32-byte secret file {@value #PROPERTY} in
 * hexadecimal. A fingerprint is {@code HMAC-SHA256(key, sha256(ruleId:credential))}: the sandbox sends
 * only the inner hash, and without the key a stored fingerprint cannot be tested against a guessed
 * credential. The key does not rotate: a new key would split every Finding from its next sighting.
 */
@Component
class FingerprintKey {

	static final String PROPERTY = "scanning-fingerprint-key";

	private final SecretKeySpec key;

	FingerprintKey(Environment environment) {
		String hex = environment.getProperty(PROPERTY);
		if (hex == null || hex.isBlank()) {
			throw new IllegalStateException("The Finding fingerprint key " + PROPERTY
					+ " is missing; it is a secret file (deploy/dev-secrets.sh creates the dev one)");
		}
		byte[] bytes = HexFormat.of().parseHex(hex.strip());
		if (bytes.length != 32) {
			throw new IllegalStateException(PROPERTY + " must hold 32 bytes in hexadecimal");
		}
		this.key = new SecretKeySpec(bytes, "HmacSHA256");
	}

	/** The fingerprint of the credential whose {@code sha256(ruleId:credential)} is {@code hash}. */
	String fingerprint(String hash) {
		if (!hash.matches("[0-9a-f]{64}")) {
			throw new IllegalArgumentException("A credential hash is 64 lower-case hexadecimal characters");
		}
		try {
			Mac mac = Mac.getInstance("HmacSHA256");
			mac.init(this.key);
			return HexFormat.of().formatHex(mac.doFinal(hash.getBytes(StandardCharsets.US_ASCII)));
		}
		catch (GeneralSecurityException ex) {
			throw new IllegalStateException("Every Java platform has HmacSHA256", ex);
		}
	}

}
