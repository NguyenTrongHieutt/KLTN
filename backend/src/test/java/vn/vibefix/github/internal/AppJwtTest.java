package vn.vibefix.github.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.security.Signature;
import java.time.Instant;
import java.util.Base64;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.JsonNode;

/** AD-10: the App JWT, from either PEM form GitHub's keys come in. */
class AppJwtTest {

	private static final Instant NOW = Instant.parse("2026-10-19T03:00:00Z");

	@Test
	void signsRs256WithTheAppAsIssuerBackdatedAndShortLived() throws Exception {
		for (String pem : new String[] { TestKeys.pkcs1(), TestKeys.pkcs8() }) {
			String jwt = AppJwt.of(123456, pem).sign(NOW);

			String[] parts = jwt.split("\\.");
			assertThat(parts).hasSize(3);
			JsonNode header = json(parts[0]);
			JsonNode payload = json(parts[1]);
			assertThat(header.path("alg").asString()).isEqualTo("RS256");
			assertThat(payload.path("iss").asString()).isEqualTo("123456");
			assertThat(payload.path("iat").asLong()).isEqualTo(NOW.getEpochSecond() - 60);
			assertThat(payload.path("exp").asLong()).isEqualTo(NOW.getEpochSecond() + 9 * 60);
			Signature verifier = Signature.getInstance("SHA256withRSA");
			verifier.initVerify(TestKeys.PAIR.getPublic());
			verifier.update((parts[0] + "." + parts[1]).getBytes(StandardCharsets.US_ASCII));
			assertThat(verifier.verify(Base64.getUrlDecoder().decode(parts[2]))).isTrue();
		}
	}

	@Test
	void refusesAFileWithoutAKeyAndNeverQuotesIt() {
		String notAKey = "-----BEGIN CERTIFICATE-----\nc2VjcmV0LWtleS1tYXRlcmlhbA==\n-----END CERTIFICATE-----";

		assertThatThrownBy(() -> AppJwt.of(1, notAKey)).isInstanceOf(IllegalArgumentException.class)
			.hasMessageNotContaining("c2VjcmV0");
		assertThatThrownBy(() -> AppJwt.of(1, "-----BEGIN PRIVATE KEY-----\nAAAA\n-----END PRIVATE KEY-----"))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageNotContaining("AAAA");
	}

	private static JsonNode json(String part) {
		return GitHubHttp.JSON.readTree(new String(Base64.getUrlDecoder().decode(part), StandardCharsets.UTF_8));
	}

}
