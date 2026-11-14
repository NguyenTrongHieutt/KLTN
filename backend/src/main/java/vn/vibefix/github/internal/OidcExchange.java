package vn.vibefix.github.internal;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.ParseException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Date;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import java.util.function.ToIntFunction;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;

import org.jspecify.annotations.Nullable;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.crypto.RSASSAVerifier;
import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import tools.jackson.databind.JsonNode;
import vn.vibefix.github.GitHubApps.InstallationToken;

/**
 * Spike S6's token exchange (the revision of 2026-11-10): a workflow run asks GitHub Actions for an
 * OIDC ID token with the audience {@value #AUDIENCE} and posts it, with the correlation it was
 * dispatched under, to {@code POST /exchange} or, as the published workflows do since Story 23.4,
 * {@code POST /github/actions/exchange} ({@code {id_token, correlation}}). The probe registers
 * each correlation before it dispatches the run ({@link #expect}), and the exchange answers 200 with a
 * Platform App token narrowed to that correlation's {@link Grant} only when every one of these holds:
 * <ul>
 * <li>an RS256 signature verified against the issuer's key set ({@link Issuer#GITHUB} unless a test
 * names another);</li>
 * <li>the issuer, {@code aud} exactly {@value #AUDIENCE}, and {@code exp}, {@code nbf} and {@code iat}
 * valid within {@link #SKEW};</li>
 * <li>{@code repository} and {@code repository_id} the correlation's repository, {@code event_name}
 * {@code workflow_dispatch}, and {@code runner_environment} {@code github-hosted};</li>
 * <li>the run {@code run_id} names, read with the repository's own App: the expected workflow file's
 * path, {@code head_sha} equal to the claim {@code workflow_sha}, {@code triggering_actor} the
 * dispatching App's bot, and a {@code display_title} that holds the correlation;</li>
 * <li>the workflow file's blob at {@code workflow_sha} equal to the published file's
 * ({@link #blobSha});</li>
 * <li>{@code ref} that repository's default branch, checked after the blob, so that a run of a changed
 * file on another branch is refused for its file;</li>
 * <li>and no earlier exchange for that correlation: one token per correlation.</li>
 * </ul>
 * A refusal of the ID token is 403 with one reason code and nothing more. Before the signature,
 * issuer, audience and times are verified it is {@code malformed}, {@code algorithm}, {@code signature},
 * {@code issuer}, {@code audience}, {@code expired} or {@code not_yet_valid}, and it is only counted
 * ({@link #unmatched}): an unverified token names no correlation it could be held against. After them it
 * is {@code correlation}, {@code replay}, {@code repository}, {@code event}, {@code runner},
 * {@code malformed}, {@code run}, {@code workflow}, {@code head_sha}, {@code actor}, {@code title},
 * {@code blob} or {@code ref}, kept on the correlation (at most {@value #MAX_REFUSALS}, then counted).
 * Other answers: 404 {@code not_found} for another path, 405 {@code method} for anything but
 * {@code POST}, 413 {@code too_large} for a body over 16 KB, 502 {@code jwks} when the key set cannot be
 * read, and 502 {@code mint} when GitHub will not mint the token.
 * <p>
 * Since Story 23.8a the handover copy also posts its path report to {@code POST /report} or
 * {@code POST /github/actions/report} ({@code {id_token, correlation, workflows_changed, paths, count}}),
 * which answers 204 once: the ID token verifies the same way, the run must be the one the correlation's
 * token was issued to, and the list is read back from the run's own gzipped bytes and kept on the
 * correlation for {@link #reported}. Nothing of a report is printed or logged here either.
 * <p>
 * The probe ends a correlation with {@link #finish} once its run is over: the token issued to it is
 * revoked then, and the correlation takes no further exchange; a token minted after that is revoked at
 * once. {@link #close} finishes every one still open, also from a shutdown hook. Nothing here prints or
 * logs anything: neither the ID token nor the token issued ever reaches stdout, stderr, or a log.
 */
final class OidcExchange implements AutoCloseable {

	static final String GITHUB_ISSUER = "https://token.actions.githubusercontent.com";

	/** The only audience accepted, and the one the workflows ask for ({@code audience=vibefix}). */
	static final String AUDIENCE = "vibefix";

	/** How far {@code exp}, {@code nbf} and {@code iat} may be off the clock. */
	static final Duration SKEW = Duration.ofSeconds(60);

	static final int DEFAULT_PORT = 8099;

	static final String PATH = "/exchange";

	/**
	 * The production exchange's path, which the published workflows post to since Story 23.4: the probe serves
	 * it too, so it stays runnable with them.
	 */
	static final String PRODUCTION_PATH = "/github/actions/exchange";

	/** The path report's own path, and the one the published handover copy posts to since Story 23.8a. */
	static final String REPORT_PATH = "/report";

	static final String PRODUCTION_REPORT_PATH = "/github/actions/report";

	/** How many refusals one correlation keeps; later ones are only counted. */
	static final int MAX_REFUSALS = 32;

	private static final int MAX_BODY_BYTES = 16 * 1024;

	/** A path report's body, as the workflow caps it before it posts. */
	private static final int MAX_REPORT_BYTES = 2 * 1024 * 1024;

	/** The paths a report may name, as the workflow and vibefix both cap them. */
	private static final int MAX_PATHS = 100_000;

	/** How far a report's list may unpack: 32 MiB of names, which no handover of a Job reaches. */
	private static final int MAX_PATH_BYTES = 32 * 1024 * 1024;

	private static final int MAX_JWKS_BYTES = 1024 * 1024;

	/** How long a key set read stays before an unknown key ID may read it again. */
	private static final Duration JWKS_REFRESH = Duration.ofSeconds(10);

	private static final Pattern CORRELATION = Pattern.compile("[a-z0-9][a-z0-9-]{7,63}");

	private static final Pattern SHA = Pattern.compile("[0-9a-f]{40}");

	private static final Pattern RUN_ID = Pattern.compile("[1-9][0-9]{0,18}");

	/** Where ID tokens come from: the issuer they name, and the key set that signs them. */
	record Issuer(String issuer, URI jwks) {

		static final Issuer GITHUB = new Issuer(GITHUB_ISSUER, URI.create(GITHUB_ISSUER + "/.well-known/jwks"));

	}

	/** What one correlation's token is narrowed to: numeric repository IDs and permissions. */
	record Grant(Set<Long> repositoryIds, Map<String, String> permissions) {

		Grant {
			repositoryIds = Set.copyOf(repositoryIds);
			permissions = Map.copyOf(permissions);
		}

	}

	/** What the exchange reads of GitHub to check a run, with the App of the repository the run is in. */
	interface Reads {

		/** {@code GET /repos/{repository}/actions/runs/{runId}}'s body, or {@code null} unless GitHub answered 200. */
		@Nullable JsonNode run(long runId);

		/** The blob SHA of {@code path} at {@code commit}, or {@code null} when GitHub has none. */
		@Nullable String blob(String path, String commit);

	}

	/**
	 * One dispatched run's right to one token.
	 * @param repository {@code owner/name} of the repository the run is in
	 * @param repositoryId its numeric ID: {@code repository_id} must name it
	 * @param defaultBranch that repository's default branch: {@code ref} must name it
	 * @param workflowPath the run's workflow file, for example {@code .github/workflows/vibefix.yml}
	 * @param workflowBlob {@link #blobSha} of the published file
	 * @param actor the dispatching App's bot, {@code <slug>[bot]}
	 * @param mint mints the Platform App token narrowed to {@code grant}
	 */
	record Expected(String correlation, String repository, long repositoryId, String defaultBranch, String workflowPath,
			String workflowBlob, String actor, Grant grant, Reads reads, Supplier<InstallationToken> mint) {
	}

	/**
	 * How one correlation's exchange ended.
	 * @param issued whether a token was issued to its run
	 * @param runId the run the token was issued to, else 0
	 * @param grant the grant the probe requested for it
	 * @param refusals the reason code of each refused attempt after verification, in order, at most
	 * {@value #MAX_REFUSALS}
	 * @param dropped the refusals past {@value #MAX_REFUSALS}, counted only
	 * @param revoked the revocation's HTTP status, or 0 when no token was issued
	 */
	record Result(String correlation, boolean issued, long runId, @Nullable Grant grant, List<String> refusals,
			int dropped, int revoked) {

		Result {
			refusals = List.copyOf(refusals);
		}

	}

	/** One answer of {@code POST /exchange}: a token for 200, else a reason code. */
	record Answer(int status, @Nullable String reason, @Nullable InstallationToken token) {
	}

	/**
	 * The path report one run posted between its fetch and its push (FR-32): every path its range changes, and
	 * whether {@code .github/workflows} differs. The probe prints the names, which are the ones it committed
	 * itself; nothing of a report is logged.
	 */
	record Report(int count, boolean workflowsChanged, List<String> paths) {

		Report {
			paths = List.copyOf(paths);
		}

	}

	/** An ID token checked on its own, before any correlation: its claims, or the answer that refuses it. */
	private sealed interface Checked {

		record Claims(JWTClaimsSet claims) implements Checked {
		}

		record Refusal(Answer answer) implements Checked {
		}

	}

	private static final class Entry {

		final Expected expected;

		final List<String> refusals = new ArrayList<>();

		int dropped;

		@Nullable InstallationToken token;

		@Nullable Report report;

		boolean used;

		boolean closed;

		long runId;

		@Nullable Result result;

		Entry(Expected expected) {
			this.expected = expected;
		}

		void refuse(String reason) {
			if (this.refusals.size() < MAX_REFUSALS) {
				this.refusals.add(reason);
			}
			else {
				this.dropped++;
			}
		}

	}

	private final Issuer issuer;

	private final Clock clock;

	private final ToIntFunction<InstallationToken> revoker;

	private final Map<String, Entry> entries = new LinkedHashMap<>();

	private final HttpClient jwksClient = HttpClient.newBuilder()
		.connectTimeout(Duration.ofSeconds(10))
		.followRedirects(HttpClient.Redirect.NEVER)
		.build();

	/** Held while closing, so a second {@link #close} (the shutdown hook's) waits for the first. */
	private final Object closing = new Object();

	private boolean closed;

	private @Nullable HttpServer server;

	private @Nullable ExecutorService executor;

	private volatile @Nullable JWKSet keys;

	private volatile @Nullable Instant keysRead;

	/** Refused attempts that named no open correlation, or were refused before verification. */
	private int unmatched;

	/** Revocations of tokens this exchange issued that GitHub answered otherwise than 204. */
	private int unrevoked;

	/**
	 * @param revoker revokes a token issued, answering GitHub's HTTP status
	 */
	OidcExchange(Issuer issuer, Clock clock, ToIntFunction<InstallationToken> revoker) {
		this.issuer = issuer;
		this.clock = clock;
		this.revoker = revoker;
	}

	/**
	 * Serves {@code POST /exchange} on {@code address} until {@link #close}.
	 * @throws IOException when the address cannot be bound
	 */
	OidcExchange listen(InetSocketAddress address) throws IOException {
		HttpServer listening = HttpServer.create(address, 0);
		ExecutorService threads = Executors.newFixedThreadPool(4, runnable -> {
			Thread thread = new Thread(runnable, "spike-s6-exchange");
			thread.setDaemon(true);
			return thread;
		});
		listening.setExecutor(threads);
		listening.createContext("/", this::handle);
		listening.start();
		this.server = listening;
		this.executor = threads;
		return this;
	}

	/** The port it listens on. */
	int port() {
		HttpServer listening = this.server;
		return listening == null ? 0 : listening.getAddress().getPort();
	}

	/** Lets one dispatched run exchange its ID token once, until {@link #finish}. */
	synchronized void expect(Expected expected) {
		if (this.entries.containsKey(expected.correlation())) {
			throw new IllegalStateException("A correlation is expected only once");
		}
		this.entries.put(expected.correlation(), new Entry(expected));
	}

	/**
	 * Ends the correlation once its run is over: the token issued to it is revoked now, and no later
	 * exchange is accepted. Finishing it again answers the same result without revoking anything twice.
	 */
	Result finish(String correlation) {
		Entry entry;
		InstallationToken token;
		synchronized (this) {
			entry = this.entries.get(correlation);
			if (entry == null) {
				return new Result(correlation, false, 0, null, List.of(), 0, 0);
			}
			if (entry.result != null) {
				return entry.result;
			}
			entry.closed = true;
			token = entry.token;
			entry.token = null;
		}
		int revoked = token == null ? 0 : revoke(token);
		synchronized (this) {
			entry.result = new Result(correlation, token != null, token != null ? entry.runId : 0, entry.expected.grant(),
					entry.refusals, entry.dropped, revoked);
			return entry.result;
		}
	}

	/** Refused attempts that named no open correlation, or were refused before verification. */
	synchronized int unmatched() {
		return this.unmatched;
	}

	/** Revocations of tokens this exchange issued, at a finish or minted too late, that GitHub did not answer 204. */
	synchronized int unrevoked() {
		return this.unrevoked;
	}

	/**
	 * Stops listening, lets the attempts in flight end, and finishes every correlation still open. A second
	 * call, the shutdown hook's or the probe's, waits for the first and then does nothing.
	 */
	@Override
	public void close() {
		synchronized (this.closing) {
			if (this.closed) {
				return;
			}
			HttpServer listening = this.server;
			ExecutorService threads = this.executor;
			if (listening != null) {
				listening.stop(0);
			}
			if (threads != null) {
				threads.shutdown();
				try {
					threads.awaitTermination(30, TimeUnit.SECONDS);
				}
				catch (InterruptedException ex) {
					Thread.currentThread().interrupt();
				}
			}
			List<String> open;
			synchronized (this) {
				open = new ArrayList<>(this.entries.keySet());
			}
			open.forEach(this::finish);
			this.closed = true;
		}
	}

	/**
	 * {@code git hash-object} of a published workflow file, its CRLF line ends read as LF: the blob SHA
	 * GitHub reports for the file once committed with LF endings.
	 */
	static String blobSha(String content) {
		byte[] bytes = content.replace("\r\n", "\n").getBytes(StandardCharsets.UTF_8);
		try {
			MessageDigest sha1 = MessageDigest.getInstance("SHA-1");
			sha1.update(("blob " + bytes.length + "\0").getBytes(StandardCharsets.US_ASCII));
			return HexFormat.of().formatHex(sha1.digest(bytes));
		}
		catch (NoSuchAlgorithmException ex) {
			throw new IllegalStateException("SHA-1 is missing", ex);
		}
	}

	private void handle(HttpExchange http) throws IOException {
		try {
			Answer answer;
			String path = http.getRequestURI().getPath();
			boolean reporting = REPORT_PATH.equals(path) || PRODUCTION_REPORT_PATH.equals(path);
			if (!reporting && !PATH.equals(path) && !PRODUCTION_PATH.equals(path)) {
				answer = new Answer(404, "not_found", null);
			}
			else if (!"POST".equals(http.getRequestMethod())) {
				answer = new Answer(405, "method", null);
			}
			else {
				int max = reporting ? MAX_REPORT_BYTES : MAX_BODY_BYTES;
				byte[] body;
				try (InputStream in = http.getRequestBody()) {
					body = in.readNBytes(max + 1);
				}
				answer = body.length > max ? new Answer(413, "too_large", null)
						: reporting ? report(body) : exchange(body);
			}
			if (answer.status() == 204) {
				// A report is taken whole or refused: 204 carries nothing back to the run.
				http.getResponseHeaders().add("Cache-Control", "no-store");
				http.sendResponseHeaders(204, -1);
				return;
			}
			Map<String, Object> json = new LinkedHashMap<>();
			if (answer.token() != null) {
				json.put("token", answer.token().value());
				json.put("expires_at", answer.token().expiresAt().toString());
			}
			else {
				json.put("reason", answer.reason());
			}
			byte[] bytes = GitHubHttp.JSON.writeValueAsBytes(json);
			http.getResponseHeaders().add("Content-Type", "application/json");
			http.getResponseHeaders().add("Cache-Control", "no-store");
			http.sendResponseHeaders(answer.status(), bytes.length);
			try (OutputStream out = http.getResponseBody()) {
				out.write(bytes);
			}
		}
		finally {
			http.close();
		}
	}

	/** One exchange: {@code {id_token, correlation}} in, a token or a reason code out. */
	Answer exchange(byte[] body) {
		JsonNode request;
		try {
			request = GitHubHttp.JSON.readTree(body);
		}
		catch (RuntimeException ex) {
			return unverified("malformed");
		}
		if (request == null || !request.isObject() || !request.path("id_token").isString()
				|| !request.path("correlation").isString()) {
			return unverified("malformed");
		}
		String correlation = request.path("correlation").stringValue();
		if (!CORRELATION.matcher(correlation).matches()) {
			return unverified("malformed");
		}
		Checked checked = verify(request.path("id_token").stringValue());
		if (checked instanceof Checked.Refusal refusal) {
			return refusal.answer();
		}
		JWTClaimsSet claims = ((Checked.Claims) checked).claims();
		// Verified from here on: refusals are kept on the correlation the token names.
		Entry entry;
		synchronized (this) {
			entry = this.entries.get(correlation);
			if (entry == null || entry.closed) {
				this.unmatched++;
				return new Answer(403, "correlation", null);
			}
			if (entry.used) {
				entry.refuse("replay");
				return new Answer(403, "replay", null);
			}
		}
		Expected expected = entry.expected;
		if (!expected.repository().equalsIgnoreCase(claim(claims, "repository"))
				|| !String.valueOf(expected.repositoryId()).equals(claim(claims, "repository_id"))) {
			return refuse(entry, "repository");
		}
		if (!"workflow_dispatch".equals(claim(claims, "event_name"))) {
			return refuse(entry, "event");
		}
		if (!"github-hosted".equals(claim(claims, "runner_environment"))) {
			return refuse(entry, "runner");
		}
		String runId = claim(claims, "run_id");
		String workflowSha = claim(claims, "workflow_sha");
		if (!RUN_ID.matcher(runId).matches() || !SHA.matcher(workflowSha).matches()) {
			return refuse(entry, "malformed");
		}
		JsonNode run;
		try {
			run = expected.reads().run(Long.parseLong(runId));
		}
		catch (RuntimeException ex) {
			run = null;
		}
		if (run == null) {
			return refuse(entry, "run");
		}
		if (!expected.workflowPath().equals(run.path("path").asString(""))) {
			return refuse(entry, "workflow");
		}
		if (!workflowSha.equals(run.path("head_sha").asString(""))) {
			return refuse(entry, "head_sha");
		}
		if (!expected.actor().equals(run.path("triggering_actor").path("login").asString(""))) {
			return refuse(entry, "actor");
		}
		if (!run.path("display_title").asString("").contains(correlation)) {
			return refuse(entry, "title");
		}
		String blob;
		try {
			blob = expected.reads().blob(expected.workflowPath(), workflowSha);
		}
		catch (RuntimeException ex) {
			blob = null;
		}
		if (!expected.workflowBlob().equals(blob)) {
			return refuse(entry, "blob");
		}
		// After the blob: a changed file on another branch is refused for its file, the same file for its ref.
		if (!("refs/heads/" + expected.defaultBranch()).equals(claim(claims, "ref"))) {
			return refuse(entry, "ref");
		}
		synchronized (this) {
			if (entry.closed) {
				entry.refuse("correlation");
				return new Answer(403, "correlation", null);
			}
			if (entry.used) {
				entry.refuse("replay");
				return new Answer(403, "replay", null);
			}
			entry.used = true;
		}
		InstallationToken token;
		try {
			token = expected.mint().get();
		}
		catch (RuntimeException ex) {
			synchronized (this) {
				entry.used = false;
				entry.refuse("mint");
			}
			return new Answer(502, "mint", null);
		}
		boolean late;
		synchronized (this) {
			late = entry.closed;
			if (!late) {
				entry.token = token;
				entry.runId = Long.parseLong(runId);
			}
			else {
				entry.refuse("correlation");
			}
		}
		if (late) {
			// The run was over before the token was minted: it never leaves the exchange.
			revoke(token);
			return new Answer(403, "correlation", null);
		}
		return new Answer(200, null, token);
	}

	/**
	 * One path report: {@code {id_token, correlation, workflows_changed, paths, count}} in, 204 or a reason code
	 * out, as vibefix's own {@code POST /github/actions/report} answers it. The list is read back from the run's
	 * own gzipped bytes and kept on the correlation. A correlation takes one report, from the run its token was
	 * issued to, after its exchange and before its {@link #finish}.
	 */
	Answer report(byte[] body) {
		JsonNode request;
		try {
			request = GitHubHttp.JSON.readTree(body);
		}
		catch (RuntimeException ex) {
			return unverified("malformed");
		}
		if (request == null || !request.isObject() || !request.path("id_token").isString()
				|| !request.path("correlation").isString() || !request.path("workflows_changed").isBoolean()
				|| !request.path("paths").isString() || !request.path("count").isIntegralNumber()) {
			return unverified("malformed");
		}
		String correlation = request.path("correlation").stringValue();
		if (!CORRELATION.matcher(correlation).matches()) {
			return unverified("malformed");
		}
		Checked checked = verify(request.path("id_token").stringValue());
		if (checked instanceof Checked.Refusal refusal) {
			return refusal.answer();
		}
		JWTClaimsSet claims = ((Checked.Claims) checked).claims();
		Entry entry;
		synchronized (this) {
			entry = this.entries.get(correlation);
			if (entry == null || entry.closed) {
				this.unmatched++;
				return new Answer(403, "correlation", null);
			}
		}
		if (!entry.expected.repository().equalsIgnoreCase(claim(claims, "repository"))) {
			return refuse(entry, "repository");
		}
		synchronized (this) {
			if (entry.runId == 0 || !String.valueOf(entry.runId).equals(claim(claims, "run_id"))) {
				// Only the run the token was issued to reports, so no other run's list is taken for this one's.
				return refuse(entry, "run");
			}
			if (entry.report != null) {
				return refuse(entry, "replay");
			}
		}
		List<String> paths = paths(request.path("paths").stringValue(), request.path("count").asLong());
		if (paths == null) {
			return refuse(entry, "report");
		}
		synchronized (this) {
			if (entry.closed || entry.report != null) {
				return refuse(entry, "correlation");
			}
			entry.report = new Report(paths.size(), request.path("workflows_changed").asBoolean(), paths);
		}
		return new Answer(204, null, null);
	}

	/** The path report one correlation's run posted, if one read back. */
	synchronized Optional<Report> reported(String correlation) {
		Entry entry = this.entries.get(correlation);
		return Optional.ofNullable(entry == null ? null : entry.report);
	}

	/**
	 * The list a report posted: base64 that decodes, gzip that unpacks within {@link #MAX_PATH_BYTES}, and
	 * exactly {@code count} NUL-separated paths, as {@code git diff -z} writes them.
	 * @return the paths, or {@code null} for anything else, which is no list of this range
	 */
	private static @Nullable List<String> paths(String encoded, long count) {
		if (count < 0 || count > MAX_PATHS) {
			return null;
		}
		byte[] gzip;
		try {
			gzip = Base64.getDecoder().decode(encoded);
		}
		catch (IllegalArgumentException notBase64) {
			return null;
		}
		byte[] plain;
		try (InputStream in = new GZIPInputStream(new ByteArrayInputStream(gzip))) {
			plain = in.readNBytes(MAX_PATH_BYTES + 1);
		}
		catch (IOException | RuntimeException notGzip) {
			return null;
		}
		if (plain.length > MAX_PATH_BYTES) {
			return null;
		}
		List<String> paths = new ArrayList<>();
		int start = 0;
		for (int at = 0; at < plain.length; at++) {
			if (plain[at] != 0) {
				continue;
			}
			if (at == start) {
				// An empty path: a leading or doubled NUL, which git never writes.
				return null;
			}
			paths.add(new String(plain, start, at - start, StandardCharsets.UTF_8));
			start = at + 1;
		}
		if (start < plain.length) {
			paths.add(new String(plain, start, plain.length - start, StandardCharsets.UTF_8));
		}
		return paths.size() == count ? paths : null;
	}

	/** An ID token on its own: the signature, the issuer, the audience and the times, within {@link #SKEW}. */
	private Checked verify(String idToken) {
		SignedJWT jwt;
		JWTClaimsSet claims;
		try {
			jwt = SignedJWT.parse(idToken);
			claims = jwt.getJWTClaimsSet();
		}
		catch (ParseException | RuntimeException ex) {
			return new Checked.Refusal(unverified("malformed"));
		}
		if (!JWSAlgorithm.RS256.equals(jwt.getHeader().getAlgorithm())) {
			return new Checked.Refusal(unverified("algorithm"));
		}
		if (this.keys == null && load() == null) {
			return new Checked.Refusal(new Answer(502, "jwks", null));
		}
		RSAKey key = key(jwt.getHeader().getKeyID());
		try {
			if (key == null || !jwt.verify(new RSASSAVerifier(key))) {
				return new Checked.Refusal(unverified("signature"));
			}
		}
		catch (JOSEException | RuntimeException ex) {
			return new Checked.Refusal(unverified("signature"));
		}
		if (!this.issuer.issuer().equals(claims.getIssuer())) {
			return new Checked.Refusal(unverified("issuer"));
		}
		if (claims.getAudience() == null || !List.of(AUDIENCE).equals(claims.getAudience())) {
			return new Checked.Refusal(unverified("audience"));
		}
		String timing = timing(claims);
		if (timing != null) {
			return new Checked.Refusal(unverified(timing));
		}
		return new Checked.Claims(claims);
	}

	/** Revokes a token this exchange issued; a status other than 204 is counted in {@link #unrevoked}. */
	private int revoke(InstallationToken token) {
		int status = this.revoker.applyAsInt(token);
		if (status != 204) {
			synchronized (this) {
				this.unrevoked++;
			}
		}
		return status;
	}

	/** A refusal before verification: counted only, since an unverified token names no correlation for sure. */
	private Answer unverified(String reason) {
		synchronized (this) {
			this.unmatched++;
		}
		return new Answer(403, reason, null);
	}

	private Answer refuse(Entry entry, String reason) {
		synchronized (this) {
			if (entry.closed) {
				this.unmatched++;
			}
			else {
				entry.refuse(reason);
			}
		}
		return new Answer(403, reason, null);
	}

	/** {@code expired}, {@code not_yet_valid}, or {@code malformed} for a missing time; {@code null} when all three hold. */
	private @Nullable String timing(JWTClaimsSet claims) {
		Date expires = claims.getExpirationTime();
		Date notBefore = claims.getNotBeforeTime();
		Date issued = claims.getIssueTime();
		if (expires == null || notBefore == null || issued == null) {
			return "malformed";
		}
		Instant now = this.clock.instant();
		if (!now.isBefore(expires.toInstant().plus(SKEW))) {
			return "expired";
		}
		if (now.plus(SKEW).isBefore(notBefore.toInstant()) || now.plus(SKEW).isBefore(issued.toInstant())) {
			return "not_yet_valid";
		}
		return null;
	}

	/** A claim as a string, or empty when it is missing or not a string. */
	private static String claim(JWTClaimsSet claims, String name) {
		try {
			String value = claims.getStringClaim(name);
			return value == null ? "" : value;
		}
		catch (ParseException ex) {
			return "";
		}
	}

	/** The issuer's RSA key of that ID, reading the key set again once when the ID is unknown. */
	private @Nullable RSAKey key(@Nullable String keyId) {
		if (keyId == null) {
			return null;
		}
		JWKSet known = this.keys;
		JWK key = known == null ? null : known.getKeyByKeyId(keyId);
		Instant read = this.keysRead;
		if (key == null && (read == null || !this.clock.instant().isBefore(read.plus(JWKS_REFRESH)))) {
			JWKSet reloaded = load();
			key = reloaded == null ? null : reloaded.getKeyByKeyId(keyId);
		}
		return key instanceof RSAKey rsa ? rsa : null;
	}

	private synchronized @Nullable JWKSet load() {
		try {
			HttpResponse<InputStream> response = this.jwksClient.send(HttpRequest.newBuilder(this.issuer.jwks())
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
			this.keysRead = this.clock.instant();
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
