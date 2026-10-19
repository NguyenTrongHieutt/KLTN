package vn.vibefix.identity.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.OptionalLong;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.api.Test;

/** AD-19: RFC 6238 codes, the step window, and the Base32 form authenticator apps read. */
class TotpTest {

	/** RFC 6238 Appendix B's SHA-1 seed. */
	private static final byte[] SEED = "12345678901234567890".getBytes(StandardCharsets.US_ASCII);

	/** RFC 6238 Appendix B's SHA-1 values, in their last 6 digits. */
	@ParameterizedTest
	@CsvSource({ "59, 287082", "1111111109, 081804", "1111111111, 050471", "1234567890, 005924",
			"2000000000, 279037", "20000000000, 353130" })
	void rfc6238Vectors(long epochSecond, String code) {
		assertThat(Totp.codeAt(SEED, Totp.step(Instant.ofEpochSecond(epochSecond)))).isEqualTo(code);
	}

	@Test
	void aCodeMatchesItsStepOrOneEitherSideAndNothingElse() {
		Instant now = Instant.ofEpochSecond(1_234_567_890);
		long step = Totp.step(now);

		assertThat(Totp.matchingStep(SEED, Totp.codeAt(SEED, step), now)).isEqualTo(OptionalLong.of(step));
		assertThat(Totp.matchingStep(SEED, Totp.codeAt(SEED, step - 1), now)).isEqualTo(OptionalLong.of(step - 1));
		assertThat(Totp.matchingStep(SEED, Totp.codeAt(SEED, step + 1), now)).isEqualTo(OptionalLong.of(step + 1));
		assertThat(Totp.matchingStep(SEED, Totp.codeAt(SEED, step - 2), now)).isEmpty();
		assertThat(Totp.matchingStep(SEED, "12345", now)).isEmpty();
		assertThat(Totp.matchingStep(SEED, "12a456", now)).isEmpty();
		assertThat(Totp.matchingStep(SEED, null, now)).isEmpty();
	}

	@Test
	void base32IsRfc4648WithoutPaddingAndRoundTrips() {
		assertThat(Totp.base32(SEED)).isEqualTo("GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ");
		byte[] secret = Totp.newSecret();
		assertThat(secret).hasSize(20);
		assertThat(Totp.fromBase32(Totp.base32(secret))).isEqualTo(secret);
	}

	@Test
	void theUriNamesTheIssuerTheAccountAndTheSecret() {
		// The RFC's public seed, encoded here rather than spelled out, so no secret-shaped literal sits in the code.
		assertThat(Totp.uri("ha.phuong@vibefix.vn", SEED)).isEqualTo("otpauth://totp/vibefix%3Aha.phuong%40vibefix.vn?secret="
				+ Totp.base32(SEED) + "&issuer=vibefix&algorithm=SHA1&digits=6&period=30");
	}

}
