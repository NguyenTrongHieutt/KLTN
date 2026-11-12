package vn.vibefix.github.internal;

import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;

import com.sun.net.httpserver.HttpExchange;

/**
 * The Customer's Source App for an integration test that runs its own small GitHub stub (Story 23.5b): every
 * contributor read goes through the Product's Source App binding, so such a test binds each Product it creates
 * ({@link #bind}) and lets this fixture answer the Source App's calls before its own routes ({@link #answer}). Each
 * Product gets an installation of its own, numbered as its repository, that selects exactly that repository under the
 * name the test gives, with AD-10's permissions; its tokens are minted, read back by the selection, and revoked. The
 * test's stub still answers {@code /repos/{owner}/{name}/contributors}, so a contributor read finds what the test
 * says, or fails closed on what it refuses. An {@link #uninstall uninstalled} installation, one never bound, and every
 * lookup by a repository's name answer 404, so an unbound Product's read fails closed.
 */
public final class SourceAppFixture {

	private static final Pattern INSTALLATION = Pattern.compile("/app/installations/(\\d+)");

	private static final Pattern ACCESS_TOKENS = Pattern.compile("/app/installations/(\\d+)/access_tokens");

	private static final Pattern REPOSITORY_INSTALLATION = Pattern.compile("/repos/[^/]+/[^/]+/installation");

	private static final String TOKEN_PREFIX = "ghs_sourcefixture_";

	/** Each installation's one selected repository, by installation ID, which is the repository's own. */
	private final Map<Long, String> selections = new ConcurrentHashMap<>();

	private final Set<Long> uninstalled = ConcurrentHashMap.newKeySet();

	/** Each token this fixture minted, with its installation. */
	private final Map<String, Long> tokens = new ConcurrentHashMap<>();

	private final AtomicInteger minted = new AtomicInteger();

	/** The tokens revoked through {@code DELETE /installation/token}, oldest first. */
	public final List<String> revoked = Collections.synchronizedList(new ArrayList<>());

	/** Each token's grant as minted: its {@code repository_ids} and {@code permissions}, as JSON text. */
	public final Map<String, String> grants = new ConcurrentHashMap<>();

	private final Path keyFile;

	public SourceAppFixture() {
		try {
			this.keyFile = Files.createTempFile("source-app-key-", ".pem");
			Files.writeString(this.keyFile, TestKeys.pkcs1(), StandardCharsets.US_ASCII);
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
	}

	/** The Source App's ID and key file, so the application signs its JWT. */
	public void register(DynamicPropertyRegistry registry) {
		registry.add("vibefix.github.source-app.id", () -> String.valueOf(GitHubStub.SOURCE_APP));
		registry.add("vibefix.github.source-app.key-file", this.keyFile::toString);
	}

	/**
	 * Binds the Product to an installation of its own, numbered {@code repositoryId}, that selects exactly its
	 * repository as {@code fullName}, as a registration through the Source App leaves it.
	 */
	public void bind(JdbcClient jdbc, UUID productId, UUID customerId, long repositoryId, String fullName) {
		this.selections.put(repositoryId, fullName);
		this.uninstalled.remove(repositoryId);
		Timestamp now = Timestamp.from(Instant.parse("2026-10-18T00:00:00Z"));
		jdbc.sql("""
				INSERT INTO product.source_app_binding (id, customer_account_id, repository_full_name, repository_github_id,
				    installation_id, account_github_id, account_login, account_type, product_id, status, created_at, bound_at)
				VALUES (:id, :customer, :name, :repository, :repository, :account, :login, 'User', :product, 'BOUND', :now, :now)
				""")
			.param("id", UUID.randomUUID())
			.param("customer", customerId)
			.param("name", fullName)
			.param("repository", repositoryId)
			.param("account", 4242L)
			.param("login", fullName.substring(0, fullName.indexOf('/')))
			.param("product", productId)
			.param("now", now)
			.update();
	}

	/** The Customer uninstalls the Product's Source App: its installation answers 404 from now on. */
	public void uninstall(long repositoryId) {
		this.uninstalled.add(repositoryId);
	}

	/** The Customer installs the Product's Source App again, as it was. */
	public void reinstall(long repositoryId) {
		this.uninstalled.remove(repositoryId);
	}

	/** Forgets the grants and revocations seen so far, for a test that counts its own. */
	public void clear() {
		this.grants.clear();
		this.revoked.clear();
	}

	/**
	 * Answers one of the Source App's calls: an installation, a token from it, its selection, or a token's revocation.
	 * @return whether it answered; anything else is left to the test's stub
	 */
	public boolean answer(HttpExchange exchange) throws IOException {
		String method = exchange.getRequestMethod();
		String path = exchange.getRequestURI().getPath();
		Matcher matcher;
		if (method.equals("GET") && REPOSITORY_INSTALLATION.matcher(path).matches()) {
			// The lookup by name finds nothing: a Product reaches its installation through its binding alone.
			send(exchange, 404, "{\"message\":\"Not Found\"}");
			return true;
		}
		if (method.equals("GET") && (matcher = INSTALLATION.matcher(path)).matches()) {
			long id = Long.parseLong(matcher.group(1));
			String repository = installed(id);
			if (repository == null) {
				send(exchange, 404, "{\"message\":\"Not Found\"}");
				return true;
			}
			Map<String, Object> installation = new LinkedHashMap<>();
			installation.put("id", id);
			installation.put("account", Map.of("id", 4242L, "login", repository.substring(0, repository.indexOf('/')), "type",
					"User"));
			installation.put("repository_selection", "selected");
			installation.put("suspended_at", "");
			installation.put("permissions", Map.of("contents", "read", "metadata", "read", "actions", "write"));
			send(exchange, 200, GitHubHttp.JSON.writeValueAsString(installation));
			return true;
		}
		if (method.equals("POST") && (matcher = ACCESS_TOKENS.matcher(path)).matches()) {
			long id = Long.parseLong(matcher.group(1));
			if (installed(id) == null) {
				send(exchange, 404, "{\"message\":\"Not Found\"}");
				return true;
			}
			String token = TOKEN_PREFIX + this.minted.incrementAndGet();
			this.tokens.put(token, id);
			this.grants.put(token, new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
			send(exchange, 201, "{\"token\":\"" + token + "\",\"expires_at\":\"2026-10-19T04:00:00Z\"}");
			return true;
		}
		String token = token(exchange);
		if (token == null) {
			return false;
		}
		if (method.equals("GET") && path.equals("/installation/repositories")) {
			long id = this.tokens.get(token);
			String repository = this.selections.get(id);
			send(exchange, 200, GitHubHttp.JSON.writeValueAsString(Map.of("total_count", 1, "repositories",
					List.of(Map.of("id", id, "full_name", repository)))));
			return true;
		}
		if (method.equals("DELETE") && path.equals("/installation/token")) {
			this.revoked.add(token);
			send(exchange, 204, "");
			return true;
		}
		return false;
	}

	/** The repository an installation selects, or none for one never bound or uninstalled. */
	private @Nullable String installed(long id) {
		return this.uninstalled.contains(id) ? null : this.selections.get(id);
	}

	/** The request's bearer token when this fixture minted it. */
	private @Nullable String token(HttpExchange exchange) {
		String authorization = exchange.getRequestHeaders().getFirst("Authorization");
		String token = authorization == null ? null : authorization.replaceFirst("^Bearer ", "");
		return token != null && this.tokens.containsKey(token) ? token : null;
	}

	private static void send(HttpExchange exchange, int status, String body) throws IOException {
		byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
		exchange.getResponseHeaders().add("Content-Type", "application/json");
		exchange.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
		if (bytes.length > 0) {
			try (OutputStream out = exchange.getResponseBody()) {
				out.write(bytes);
			}
		}
		exchange.close();
	}

}
