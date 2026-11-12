package vn.vibefix.github.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpServer;

import vn.vibefix.github.ExchangeReason;
import vn.vibefix.support.MutableClock;

/**
 * Story 23.4 (AD-44), the ID token checks promoted from Spike S6's exchange: an RS256 signature against the
 * issuer's key set, the issuer, {@code aud} exactly {@code vibefix}, and {@code exp}, {@code nbf} and {@code iat}
 * within 60 s. A malformed token, another algorithm, a foreign key and a key set that cannot be read are all
 * {@code signature}; the key set is read once, again for an unknown key ID at most every 10 s, and never past
 * 1 MB.
 */
class ActionsIdTokensTest {

	private static final Instant NOW = Instant.parse("2026-11-12T03:00:00Z");

	private static final String KEY_ID = "test-key";

	private static final RSAKey KEY = TestKeys.jwk(KEY_ID);

	private final MutableClock clock = new MutableClock(NOW);

	private final Map<String, Object> claims = new LinkedHashMap<>();

	private final AtomicInteger reads = new AtomicInteger();

	private volatile String keySet = new JWKSet(KEY.toPublicJWK()).toString();

	private HttpServer issuer;

	private String issuerName;

	private ActionsIdTokens tokens;

	@BeforeEach
	void anIssuer() throws IOException {
		this.issuer = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
		this.issuer.createContext("/.well-known/jwks", http -> {
			this.reads.incrementAndGet();
			byte[] bytes = this.keySet.getBytes(StandardCharsets.UTF_8);
			http.sendResponseHeaders(200, bytes.length);
			try (OutputStream out = http.getResponseBody()) {
				out.write(bytes);
			}
			http.close();
		});
		this.issuer.start();
		this.issuerName = "http://127.0.0.1:" + this.issuer.getAddress().getPort();
		this.tokens = new ActionsIdTokens(new GitHubProperties(URI.create("https://api.github.com"), null, "vibefix-dev", null,
				null, null, null, new GitHubProperties.Exchange(null, null, null, this.issuerName)), this.clock);
		this.claims.put("iss", this.issuerName);
		this.claims.put("aud", ActionsIdTokens.AUDIENCE);
		this.claims.put("iat", Date.from(NOW.minusSeconds(5)));
		this.claims.put("nbf", Date.from(NOW.minusSeconds(5)));
		this.claims.put("exp", Date.from(NOW.plus(Duration.ofMinutes(5))));
		this.claims.put("repository", "lan-shop/quan-may");
	}

	@AfterEach
	void stop() {
		this.issuer.stop(0);
	}

	@Test
	void aGenuineTokenGivesItsClaims() throws Exception {
		ActionsIdTokens.Verified verified = this.tokens.verify(sign(KEY));

		assertThat(verified.refusal()).isNull();
		assertThat(ActionsIdTokens.claim(verified.claims(), "repository")).isEqualTo("lan-shop/quan-may");
		assertThat(ActionsIdTokens.claim(verified.claims(), "absent")).isEmpty();
	}

	@Test
	void timesWithinTheSkewAreAccepted() throws Exception {
		this.claims.put("exp", Date.from(NOW.minusSeconds(50)));
		this.claims.put("nbf", Date.from(NOW.plusSeconds(50)));
		this.claims.put("iat", Date.from(NOW.plusSeconds(50)));

		assertThat(this.tokens.verify(sign(KEY)).refusal()).isNull();
	}

	@ParameterizedTest(name = "{0} is refused as {1}")
	@CsvSource({ "a foreign key, SIGNATURE", "HS256, SIGNATURE", "not a JWT, SIGNATURE", "no key ID, SIGNATURE",
			"another issuer, ISSUER", "another audience, AUDIENCE", "two audiences, AUDIENCE", "an expired token, EXPIRY",
			"a token not yet valid, EXPIRY", "a token issued later, EXPIRY", "no expiry, EXPIRY" })
	void anythingButAGenuineTokenIsRefusedWithOneReason(String flaw, ExchangeReason reason) throws Exception {
		String token = switch (flaw) {
			case "a foreign key" -> sign(TestKeys.foreignJwk(KEY_ID));
			case "HS256" -> {
				SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.HS256).keyID(KEY_ID).build(), claims());
				jwt.sign(new MACSigner("ab".repeat(32).getBytes(StandardCharsets.US_ASCII)));
				yield jwt.serialize();
			}
			case "not a JWT" -> "not-a-jwt";
			case "no key ID" -> {
				SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).build(), claims());
				jwt.sign(new RSASSASigner(KEY));
				yield jwt.serialize();
			}
			default -> {
				switch (flaw) {
					case "another issuer" -> this.claims.put("iss", "https://token.actions.githubusercontent.com");
					case "another audience" -> this.claims.put("aud", "sts.amazonaws.com");
					case "two audiences" -> this.claims.put("aud", List.of(ActionsIdTokens.AUDIENCE, "sts.amazonaws.com"));
					case "an expired token" -> this.claims.put("exp", Date.from(NOW.minusSeconds(61)));
					case "a token not yet valid" -> this.claims.put("nbf", Date.from(NOW.plusSeconds(120)));
					case "a token issued later" -> this.claims.put("iat", Date.from(NOW.plusSeconds(120)));
					case "no expiry" -> this.claims.remove("exp");
					default -> throw new IllegalArgumentException(flaw);
				}
				yield sign(KEY);
			}
		};

		ActionsIdTokens.Verified verified = this.tokens.verify(token);

		assertThat(verified.refusal()).isEqualTo(reason);
		assertThat(verified.claims()).isNull();
	}

	@Test
	void aKeySetThatCannotBeReadIsASignatureRefusal() throws Exception {
		this.issuer.stop(0);

		assertThat(this.tokens.verify(sign(KEY)).refusal()).isEqualTo(ExchangeReason.SIGNATURE);
	}

	@Test
	void theKeySetIsKeptAndReadAgainForAnUnknownKeyAtMostEveryTenSeconds() throws Exception {
		assertThat(this.tokens.verify(sign(KEY)).refusal()).isNull();
		assertThat(this.tokens.verify(sign(KEY)).refusal()).isNull();
		assertThat(this.reads).as("read once, then kept").hasValue(1);

		RSAKey rotated = new RSAKey.Builder(TestKeys.foreignJwk("rotated")).keyID("rotated").build();
		this.keySet = new JWKSet(List.of(KEY.toPublicJWK(), rotated.toPublicJWK())).toString();
		assertThat(this.tokens.verify(sign(rotated, "rotated")).refusal()).as("read again a moment ago: not yet")
			.isEqualTo(ExchangeReason.SIGNATURE);
		this.clock.advance(ActionsIdTokens.JWKS_REFRESH);
		assertThat(this.tokens.verify(sign(rotated, "rotated")).refusal()).as("an unknown key reads the set again")
			.isNull();
		assertThat(this.reads).hasValue(2);
	}

	@Test
	void aKeyTheIssuerRemovedStopsVerifyingOnceTheSetIsAnHourOld() throws Exception {
		assertThat(this.tokens.verify(sign(KEY)).refusal()).isNull();
		RSAKey rotated = new RSAKey.Builder(TestKeys.foreignJwk("rotated")).keyID("rotated").build();
		this.keySet = new JWKSet(rotated.toPublicJWK()).toString();

		this.clock.advance(ActionsIdTokens.JWKS_MAX_AGE.minusSeconds(1));
		issuedNow();
		assertThat(this.tokens.verify(sign(KEY)).refusal()).as("the kept set still holds it").isNull();
		assertThat(this.reads).hasValue(1);

		this.clock.advance(Duration.ofSeconds(1));
		issuedNow();
		assertThat(this.tokens.verify(sign(KEY)).refusal()).as("an hour old, the set is read again without it")
			.isEqualTo(ExchangeReason.SIGNATURE);
		assertThat(this.reads).hasValue(2);
		assertThat(this.tokens.verify(sign(KEY)).refusal()).isEqualTo(ExchangeReason.SIGNATURE);
		assertThat(this.reads).as("not read again within 10 seconds").hasValue(2);
	}

	@Test
	void aKeySetOverOneMegabyteIsNotRead() throws Exception {
		this.keySet = "{\"keys\":[],\"padding\":\"" + "x".repeat(1024 * 1024) + "\"}";

		assertThat(this.tokens.verify(sign(KEY)).refusal()).isEqualTo(ExchangeReason.SIGNATURE);
	}

	/** The token's times, as GitHub would sign it at the clock's present. */
	private void issuedNow() {
		Instant now = this.clock.instant();
		this.claims.put("iat", Date.from(now.minusSeconds(5)));
		this.claims.put("nbf", Date.from(now.minusSeconds(5)));
		this.claims.put("exp", Date.from(now.plus(Duration.ofMinutes(5))));
	}

	private String sign(RSAKey key) throws JOSEException {
		return sign(key, KEY_ID);
	}

	private String sign(RSAKey key, String keyId) throws JOSEException {
		SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(keyId).build(), claims());
		jwt.sign(new RSASSASigner(key));
		return jwt.serialize();
	}

	private JWTClaimsSet claims() {
		JWTClaimsSet.Builder builder = new JWTClaimsSet.Builder();
		this.claims.forEach(builder::claim);
		return builder.build();
	}

}
