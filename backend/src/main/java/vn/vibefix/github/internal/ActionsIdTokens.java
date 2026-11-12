package vn.vibefix.github.internal;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.text.ParseException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.List;

import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.crypto.RSASSAVerifier;
import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;

import vn.vibefix.github.ExchangeReason;

/**
 * Verifies a GitHub Actions OIDC ID token (AD-44), promoted from Spike S6's exchange: an RS256 signature
 * against the issuer's key set, the issuer, the audience {@value #AUDIENCE} alone, and {@code exp},
 * {@code nbf} and {@code iat} within {@link #SKEW}. The key set is read from {@code <issuer>/.well-known/jwks}
 * (1 MB at most), kept, and read again for an unknown key ID or once it is {@link #JWKS_MAX_AGE} old, at most
 * every {@link #JWKS_REFRESH}. A malformed
 * token, another algorithm, and a key set that cannot be read all count as {@code signature}. Nothing here
 * logs or keeps the token.
 */
@Component
class ActionsIdTokens {

	/** The only audience accepted, the one the workflows ask for ({@code audience=vibefix}). */
	static final String AUDIENCE = "vibefix";

	/** How far {@code exp}, {@code nbf} and {@code iat} may be off the clock. */
	static final Duration SKEW = Duration.ofSeconds(60);

	/** How long a key set read stays before an unknown key ID, or an old set, may read it again. */
	static final Duration JWKS_REFRESH = Duration.ofSeconds(10);

	/** How long a key set is used before it is read again, so a key the issuer removed stops verifying. */
	static final Duration JWKS_MAX_AGE = Duration.ofHours(1);

	private static final int MAX_JWKS_BYTES = 1024 * 1024;

	/** A verified token's claims, or the reason it is refused. */
	record Verified(@Nullable JWTClaimsSet claims, @Nullable ExchangeReason refusal) {

		static Verified refused(ExchangeReason reason) {
			return new Verified(null, reason);
		}

	}

	private final String issuer;

	private final URI jwks;

	private final Clock clock;

	private final HttpClient http = HttpClient.newBuilder()
		.connectTimeout(Duration.ofSeconds(10))
		.followRedirects(HttpClient.Redirect.NEVER)
		.build();

	private volatile @Nullable JWKSet keys;

	/** When the key set was last asked for, whether or not the read succeeded. */
	private volatile @Nullable Instant keysRead;

	/** When the key set now kept was read. */
	private volatile @Nullable Instant keysLoaded;

	ActionsIdTokens(GitHubProperties properties, Clock clock) {
		this.issuer = properties.exchange().issuer();
		this.jwks = properties.exchange().jwks();
		this.clock = clock;
	}

	/** The token's claims once the signature, issuer, audience and times hold; else the first refusal. */
	Verified verify(String idToken) {
		SignedJWT jwt;
		JWTClaimsSet claims;
		try {
			jwt = SignedJWT.parse(idToken);
			claims = jwt.getJWTClaimsSet();
		}
		catch (ParseException | RuntimeException ex) {
			return Verified.refused(ExchangeReason.SIGNATURE);
		}
		if (!JWSAlgorithm.RS256.equals(jwt.getHeader().getAlgorithm())) {
			return Verified.refused(ExchangeReason.SIGNATURE);
		}
		RSAKey key = key(jwt.getHeader().getKeyID());
		try {
			if (key == null || !jwt.verify(new RSASSAVerifier(key))) {
				return Verified.refused(ExchangeReason.SIGNATURE);
			}
		}
		catch (JOSEException | RuntimeException ex) {
			return Verified.refused(ExchangeReason.SIGNATURE);
		}
		if (!this.issuer.equals(claims.getIssuer())) {
			return Verified.refused(ExchangeReason.ISSUER);
		}
		if (claims.getAudience() == null || !List.of(AUDIENCE).equals(claims.getAudience())) {
			return Verified.refused(ExchangeReason.AUDIENCE);
		}
		if (!timely(claims)) {
			return Verified.refused(ExchangeReason.EXPIRY);
		}
		return new Verified(claims, null);
	}

	/** Whether {@code exp}, {@code nbf} and {@code iat} are all present and hold within {@link #SKEW}. */
	private boolean timely(JWTClaimsSet claims) {
		Date expires = claims.getExpirationTime();
		Date notBefore = claims.getNotBeforeTime();
		Date issued = claims.getIssueTime();
		if (expires == null || notBefore == null || issued == null) {
			return false;
		}
		Instant now = this.clock.instant();
		return now.isBefore(expires.toInstant().plus(SKEW)) && !now.plus(SKEW).isBefore(notBefore.toInstant())
				&& !now.plus(SKEW).isBefore(issued.toInstant());
	}

	/** A claim as a string, or empty when it is missing or not a string. */
	static String claim(JWTClaimsSet claims, String name) {
		try {
			String value = claims.getStringClaim(name);
			return value == null ? "" : value;
		}
		catch (ParseException ex) {
			return "";
		}
	}

	/**
	 * The issuer's RSA key of that ID, reading the key set again when the ID is unknown or the set was read
	 * {@link #JWKS_MAX_AGE} ago, so a key GitHub removed stops verifying; never more often than every
	 * {@link #JWKS_REFRESH}.
	 */
	private @Nullable RSAKey key(@Nullable String keyId) {
		if (keyId == null) {
			return null;
		}
		JWKSet known = this.keys;
		JWK key = known == null ? null : known.getKeyByKeyId(keyId);
		Instant now = this.clock.instant();
		Instant read = this.keysRead;
		Instant loaded = this.keysLoaded;
		boolean stale = loaded == null || !now.isBefore(loaded.plus(JWKS_MAX_AGE));
		if ((key == null || stale) && (read == null || !now.isBefore(read.plus(JWKS_REFRESH)))) {
			JWKSet reloaded = load();
			key = reloaded == null ? null : reloaded.getKeyByKeyId(keyId);
		}
		return key instanceof RSAKey rsa ? rsa : null;
	}

	private synchronized @Nullable JWKSet load() {
		Instant read = this.keysRead;
		if (read != null && this.clock.instant().isBefore(read.plus(JWKS_REFRESH))) {
			// Another thread read it a moment ago.
			return this.keys;
		}
		this.keysRead = this.clock.instant();
		try {
			HttpResponse<InputStream> response = this.http.send(HttpRequest.newBuilder(this.jwks)
				.timeout(Duration.ofSeconds(10))
				.header("Accept", "application/json")
				.GET()
				.build(), HttpResponse.BodyHandlers.ofInputStream());
			byte[] bytes;
			try (InputStream in = response.body()) {
				bytes = in.readNBytes(MAX_JWKS_BYTES + 1);
			}
			if (response.statusCode() != 200 || bytes.length > MAX_JWKS_BYTES) {
				return this.keys;
			}
			JWKSet loaded = JWKSet.parse(new String(bytes, StandardCharsets.UTF_8));
			this.keys = loaded;
			this.keysLoaded = this.keysRead;
			return loaded;
		}
		catch (IOException | ParseException | IllegalArgumentException ex) {
			return this.keys;
		}
		catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
			return this.keys;
		}
	}

}
