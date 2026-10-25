package vn.vibefix.identity.internal;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import org.jspecify.annotations.Nullable;

import tools.jackson.databind.json.JsonMapper;

/**
 * A stand-in for Google's OpenID Connect endpoints (Story 19.1), on a random local port, for the
 * whole sign-in flow in tests: the token endpoint answers an access token and an RS256 ID token signed
 * by a key built at run time, with the nonce the test read from the authorization redirect; the key
 * set endpoint publishes that key; the userinfo endpoint answers the same user. The browser's step
 * (Google's consent page) is skipped: the test calls the callback with the {@code state} the
 * application generated.
 */
public final class StubGoogle implements AutoCloseable {

	/** How the next ID token is spoiled, for the tests that the application refuses it. */
	public enum Flaw {

		/** A good token. */
		NONE,

		/** A nonce other than the authorization request's. */
		NONCE,

		/** Issued for another client. */
		AUDIENCE,

		/** Issued by another issuer. */
		ISSUER,

		/** Expired well beyond the allowed clock skew. */
		EXPIRED,

		/** Signed by a key the key set does not publish, under the same key ID. */
		FOREIGN_KEY

	}

	static final String CLIENT_ID = "stub-google-client";

	private static final String GOOGLE_SCOPES = "openid https://www.googleapis.com/auth/userinfo.email "
			+ "https://www.googleapis.com/auth/userinfo.profile";

	private static final StubGoogle INSTANCE = new StubGoogle();

	private static final JsonMapper JSON = JsonMapper.builder().build();

	private final HttpServer server;

	private final RSAKey key;

	private final RSAKey foreignKey;

	private final AtomicInteger tokens = new AtomicInteger();

	private final List<String> issued = new CopyOnWriteArrayList<>();

	private volatile String subject = "";

	private volatile String email = "";

	private volatile @Nullable String name;

	private volatile @Nullable Boolean emailVerified = Boolean.TRUE;

	private volatile String nonce = "";

	private volatile Flaw flaw = Flaw.NONE;

	private StubGoogle() {
		try {
			this.key = new RSAKeyGenerator(2048).keyID("stub-google-key").generate();
			this.foreignKey = new RSAKeyGenerator(2048).keyID("stub-google-key").generate();
			this.server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		}
		catch (IOException | JOSEException ex) {
			throw new IllegalStateException(ex);
		}
		this.server.createContext("/token", this::token);
		this.server.createContext("/certs", this::certs);
		this.server.createContext("/userinfo", this::userinfo);
		this.server.start();
	}

	public static StubGoogle instance() {
		return INSTANCE;
	}

	/** The issuer, and the base of every endpoint. */
	public String baseUrl() {
		return "http://127.0.0.1:" + this.server.getAddress().getPort();
	}

	/** The user Google answers next, with a verified email and a good ID token. */
	public void signsInAs(String subject, String email, @Nullable String name) {
		this.subject = subject;
		this.email = email;
		this.name = name;
		this.emailVerified = Boolean.TRUE;
		this.flaw = Flaw.NONE;
	}

	/** Spoils the next ID tokens so; {@link #signsInAs} makes them good again. */
	public void flaw(Flaw flaw) {
		this.flaw = flaw;
	}

	/** The {@code email_verified} claim of the next answers; {@code null} leaves it out. */
	public void emailVerified(@Nullable Boolean verified) {
		this.emailVerified = verified;
	}

	/** The nonce of the authorization request the next ID token answers. */
	public void nonce(String nonce) {
		this.nonce = nonce;
	}

	/** Every access token and ID token issued, to check that none is kept. */
	public List<String> issued() {
		return this.issued;
	}

	private void token(HttpExchange exchange) throws IOException {
		exchange.getRequestBody().readAllBytes();
		String accessToken = "stub-google-access-" + this.tokens.incrementAndGet();
		Flaw flaw = this.flaw;
		Instant now = flaw == Flaw.EXPIRED ? Instant.now().minusSeconds(3600) : Instant.now();
		JWTClaimsSet.Builder claims = new JWTClaimsSet.Builder()
			.issuer(flaw == Flaw.ISSUER ? "https://issuer.example.test" : baseUrl())
			.subject(this.subject)
			.audience(flaw == Flaw.AUDIENCE ? "another-client" : CLIENT_ID)
			.issueTime(Date.from(now))
			.expirationTime(Date.from(now.plusSeconds(300)))
			.claim("nonce", flaw == Flaw.NONCE ? "another-nonce" : this.nonce)
			.claim("email", this.email);
		if (this.emailVerified != null) {
			claims.claim("email_verified", this.emailVerified);
		}
		if (this.name != null) {
			claims.claim("name", this.name);
		}
		SignedJWT idToken = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(this.key.getKeyID()).build(),
				claims.build());
		try {
			idToken.sign(new RSASSASigner(flaw == Flaw.FOREIGN_KEY ? this.foreignKey : this.key));
		}
		catch (JOSEException ex) {
			throw new IOException(ex);
		}
		String serialized = idToken.serialize();
		this.issued.add(accessToken);
		this.issued.add(serialized);
		// The scopes as Google names them, so Spring reads the claims from the ID token, as it does with Google.
		respond(exchange, JSON.writeValueAsString(Map.of("access_token", accessToken, "token_type", "Bearer", "expires_in",
				300, "scope", GOOGLE_SCOPES, "id_token", serialized)));
	}

	private void certs(HttpExchange exchange) throws IOException {
		respond(exchange, new JWKSet(this.key.toPublicJWK()).toString());
	}

	private void userinfo(HttpExchange exchange) throws IOException {
		String authorization = exchange.getRequestHeaders().getFirst("Authorization");
		if (authorization == null || !authorization.startsWith("Bearer stub-google-access-")) {
			exchange.sendResponseHeaders(401, -1);
			exchange.close();
			return;
		}
		Map<String, Object> user = new LinkedHashMap<>();
		user.put("sub", this.subject);
		user.put("email", this.email);
		if (this.emailVerified != null) {
			user.put("email_verified", this.emailVerified);
		}
		if (this.name != null) {
			user.put("name", this.name);
		}
		respond(exchange, JSON.writeValueAsString(user));
	}

	private static void respond(HttpExchange exchange, String json) throws IOException {
		byte[] body = json.getBytes(StandardCharsets.UTF_8);
		exchange.getResponseHeaders().add("Content-Type", "application/json");
		exchange.sendResponseHeaders(200, body.length);
		try (OutputStream out = exchange.getResponseBody()) {
			out.write(body);
		}
	}

	@Override
	public void close() {
		this.server.stop(0);
	}

}
