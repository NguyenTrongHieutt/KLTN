package vn.vibefix.identity.internal;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.OptionalLong;

import javax.crypto.spec.SecretKeySpec;

import com.eatthepath.otp.TimeBasedOneTimePasswordGenerator;

/**
 * RFC 6238 time-based one-time codes (AD-19), through {@code java-otp}: HMAC-SHA1, 30 s steps, 6
 * digits, and one step of clock drift either way. The caller makes each accepted step single-use.
 */
final class Totp {

	static final Duration STEP = Duration.ofSeconds(30);

	static final int DIGITS = 6;

	/** 160 bits, the length RFC 4226 recommends for HMAC-SHA1. */
	static final int SECRET_BYTES = 20;

	private static final String BASE32 = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";

	private static final SecureRandom RANDOM = new SecureRandom();

	private Totp() {
	}

	static byte[] newSecret() {
		byte[] secret = new byte[SECRET_BYTES];
		RANDOM.nextBytes(secret);
		return secret;
	}

	/** The step of the 6-digit {@code code} for {@code secret} at {@code now}, or one step either side. */
	static OptionalLong matchingStep(byte[] secret, String code, Instant now) {
		if (code == null || !code.matches("[0-9]{" + DIGITS + "}")) {
			return OptionalLong.empty();
		}
		long current = step(now);
		for (long step = current - 1; step <= current + 1; step++) {
			if (codeAt(secret, step).equals(code)) {
				return OptionalLong.of(step);
			}
		}
		return OptionalLong.empty();
	}

	/** The code of {@code secret} for one step. */
	static String codeAt(byte[] secret, long step) {
		TimeBasedOneTimePasswordGenerator generator = new TimeBasedOneTimePasswordGenerator(STEP, DIGITS);
		try {
			int value = generator.generateOneTimePassword(new SecretKeySpec(secret, generator.getAlgorithm()),
					Instant.ofEpochSecond(step * STEP.toSeconds()));
			return "%06d".formatted(value);
		}
		catch (InvalidKeyException ex) {
			throw new IllegalStateException("An HMAC-SHA1 key accepts any length", ex);
		}
	}

	static long step(Instant at) {
		return Math.floorDiv(at.getEpochSecond(), STEP.toSeconds());
	}

	/** RFC 4648 Base32 without padding, the form authenticator apps read. */
	static String base32(byte[] bytes) {
		StringBuilder out = new StringBuilder();
		int buffer = 0;
		int bits = 0;
		for (byte b : bytes) {
			buffer = (buffer << 8) | (b & 0xff);
			bits += 8;
			while (bits >= 5) {
				out.append(BASE32.charAt((buffer >> (bits - 5)) & 31));
				bits -= 5;
			}
		}
		if (bits > 0) {
			out.append(BASE32.charAt((buffer << (5 - bits)) & 31));
		}
		return out.toString();
	}

	/** Decodes {@link #base32}'s output. */
	static byte[] fromBase32(String text) {
		java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
		int buffer = 0;
		int bits = 0;
		for (char c : text.toCharArray()) {
			int value = BASE32.indexOf(c);
			if (value < 0) {
				throw new IllegalArgumentException("Not Base32: " + c);
			}
			buffer = (buffer << 5) | value;
			bits += 5;
			if (bits >= 8) {
				out.write((buffer >> (bits - 8)) & 0xff);
				bits -= 8;
			}
		}
		return out.toByteArray();
	}

	/** The {@code otpauth://} URI an authenticator app scans or imports. */
	static String uri(String email, byte[] secret) {
		String label = URLEncoder.encode("vibefix:" + email, StandardCharsets.UTF_8).replace("+", "%20");
		return "otpauth://totp/" + label + "?secret=" + base32(secret) + "&issuer=vibefix&algorithm=SHA1&digits="
				+ DIGITS + "&period=" + STEP.toSeconds();
	}

}
