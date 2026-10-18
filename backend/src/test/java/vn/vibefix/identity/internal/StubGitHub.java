package vn.vibefix.identity.internal;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

/**
 * A stand-in for GitHub's OAuth endpoints and its {@code /user} API (AD-10), on a random local port,
 * for the whole sign-in flow in tests: it issues a new access token per code and answers the user
 * the test sets. The browser's step (GitHub's authorization page) is skipped: the test calls the
 * callback with the {@code state} the application generated.
 */
public final class StubGitHub implements AutoCloseable {

	private static final StubGitHub INSTANCE = new StubGitHub();

	private final HttpServer server;

	private final AtomicInteger tokens = new AtomicInteger();

	private final List<String> tokenRequests = new CopyOnWriteArrayList<>();

	private volatile String userJson = "{}";

	private volatile String emailsJson = "[]";

	private volatile int emailsStatus = 200;

	private StubGitHub() {
		try {
			this.server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		}
		catch (IOException ex) {
			throw new IllegalStateException(ex);
		}
		this.server.createContext("/login/oauth/access_token", this::token);
		this.server.createContext("/user", this::user);
		this.server.createContext("/user/emails", this::emails);
		this.server.start();
	}

	public static StubGitHub instance() {
		return INSTANCE;
	}

	public String baseUrl() {
		return "http://127.0.0.1:" + this.server.getAddress().getPort();
	}

	/**
	 * The user GitHub answers next: numeric ID, login, avatar, and as email addresses an unverified
	 * one, a verified secondary one, and the primary verified {@code {login}@example.test}.
	 */
	public void signsInAs(long id, String login) {
		this.userJson = "{\"id\":" + id + ",\"login\":\"" + login + "\",\"avatar_url\":\"https://avatars.example/u/" + id
				+ "\",\"email\":\"" + login + "@example.test\"}";
		this.emailsJson = "[{\"email\":\"" + login + "@unverified.test\",\"primary\":false,\"verified\":false},"
				+ "{\"email\":\"" + login + "@school.test\",\"primary\":false,\"verified\":true},"
				+ "{\"email\":\"" + login + "@example.test\",\"primary\":true,\"verified\":true}]";
		this.emailsStatus = 200;
	}

	/** GitHub's email addresses answer {@code status} for the next sign-ins. */
	public void emailsAnswer(int status) {
		this.emailsStatus = status;
	}

	/** The last access token issued. */
	public String lastToken() {
		return "gho_stub_" + this.tokens.get();
	}

	/** The form bodies of the token requests the application sent. */
	public List<String> tokenRequests() {
		return this.tokenRequests;
	}

	private void token(HttpExchange exchange) throws IOException {
		this.tokenRequests.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
		String token = "gho_stub_" + this.tokens.incrementAndGet();
		respond(exchange, "{\"access_token\":\"" + token + "\",\"token_type\":\"bearer\",\"scope\":\"read:user,user:email\"}");
	}

	private void user(HttpExchange exchange) throws IOException {
		String authorization = exchange.getRequestHeaders().getFirst("Authorization");
		if (authorization == null || !authorization.startsWith("Bearer gho_stub_")) {
			exchange.sendResponseHeaders(401, -1);
			exchange.close();
			return;
		}
		respond(exchange, this.userJson);
	}

	private void emails(HttpExchange exchange) throws IOException {
		String authorization = exchange.getRequestHeaders().getFirst("Authorization");
		if (authorization == null || !authorization.startsWith("Bearer gho_stub_")) {
			exchange.sendResponseHeaders(401, -1);
			exchange.close();
			return;
		}
		if (this.emailsStatus != 200) {
			exchange.sendResponseHeaders(this.emailsStatus, -1);
			exchange.close();
			return;
		}
		respond(exchange, this.emailsJson);
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
