package vn.vibefix.github.internal;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.Date;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.jspecify.annotations.Nullable;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import tools.jackson.databind.JsonNode;
import vn.vibefix.github.GitHubApps.App;

/**
 * Spike S6's stand-in for GitHub (the revision of 2026-11-10), on loopback: the Platform App and the
 * Source App, each told apart by its JWT's issuer and installed where the design puts it; installation
 * tokens checked against the installation, their {@code repository_ids} and their permissions;
 * repositories with branches as lists of commits and a tree per commit; the organization's Actions
 * policy; workflow dispatches, runs, jobs, timings, logs behind a redirect, and their deletion;
 * invitations; and pull requests. It is also the OIDC issuer: it publishes a key set and signs each run's
 * ID token with a test key.
 * <p>
 * A dispatched run does its work at once, as the published workflow would: it signs its ID token (with
 * the {@link Flaw} a test asks for), posts it to the run's {@code exchange_url}, and with the token it is
 * given copies only what that token reaches, never pushing with force. A Customer's run reads and writes
 * its own repository as its {@code GITHUB_TOKEN} would. Whether a run exchanges at all is read from the
 * dispatched file's own {@code if:} on its exchange step: a {@code vibefix.yml} scan skips it, asks for no
 * ID token, and logs its count of findings ({@link #scanFindings}) or, with {@link #scanLineMissing},
 * stops at the download without one. Every request, token, revocation, exchange answer, ID token, and the
 * order of runs completing and tokens being revoked are recorded.
 */
final class SpikeS6Stub implements AutoCloseable {

	static final String ORGANIZATION = "vibefix-dev";

	static final long PLATFORM_APP = 123456;

	static final long SOURCE_APP = 2222;

	static final long PLATFORM_INSTALLATION = 7;

	static final long SOURCE_INSTALLATION = 21;

	static final String PLATFORM_SLUG = "vibefix-dev-platform";

	static final String SOURCE_SLUG = "vibefix-dev-source";

	/** The Customer's private Source Repository. */
	static final String SOURCE = "lan-shop/quan-may-private";

	static final String RUNNER_NAME = "vibefix-runner";

	static final String RUNNER = ORGANIZATION + "/" + RUNNER_NAME;

	/** The Customer, admin of {@link #SOURCE}. */
	static final String CUSTOMER = "lan-shop";

	static final String CUSTOMER_PATH = ".github/workflows/" + SpikeS6Command.CUSTOMER_WORKFLOW;

	static final String FREE_RULESET = "Upgrade to GitHub Pro or make this repository public to enable this feature.";

	static final String KEY_ID = "s6-stub-key";

	/** The lines of a failed step's log: line 1 to 60, a masked token and the refusal last. */
	static final int LOG_LINES = 60;

	/** The file and rule of the finding a scan's log names, as a workflow printing gitleaks' own output would. */
	static final String FINDING_FILE = "config/payment.properties";

	static final String FINDING_RULE = "vnpay-hash-secret";

	/** How a run's ID token, or the run GitHub shows, differs from a genuine one. */
	enum Flaw {

		NONE,

		/** Signed by a key the key set does not publish, under the same key ID. */
		SIGNATURE,

		/** Issued for another audience. */
		AUDIENCE,

		/** Expired ten minutes ago. */
		EXPIRED,

		/** Issued by another issuer. */
		ISSUER,

		/** The run's job ran on a self-hosted runner. */
		SELF_HOSTED,

		/** The run was triggered by someone other than the dispatching App. */
		FOREIGN_ACTOR,

		/** The run's title holds no correlation. */
		FOREIGN_TITLE,

		/** The run names a correlation the probe never dispatched. */
		UNKNOWN_CORRELATION,

		/** The run exchanges its ID token a second time. */
		REPLAY

	}

	/** One request as GitHub would have received it. */
	record Seen(String method, String path, @Nullable String query, @Nullable String authorization, JsonNode body) {
	}

	/** What a token was minted for. */
	record Grant(long installation, Set<Long> repositoryIds, Map<String, String> permissions) {
	}

	/** What one run's call to the exchange was answered. */
	record Exchanged(long runId, String correlation, int status, @Nullable String reason, @Nullable String token) {
	}

	final List<Seen> seen = Collections.synchronizedList(new ArrayList<>());

	/** Every token handed out, oldest first. */
	final List<String> tokens = Collections.synchronizedList(new ArrayList<>());

	final Map<String, Grant> grants = Collections.synchronizedMap(new LinkedHashMap<>());

	final Set<String> revoked = Collections.synchronizedSet(new HashSet<>());

	/** {@code completed <run>} and {@code revoked <token>}, in the order they happened. */
	final List<String> events = Collections.synchronizedList(new ArrayList<>());

	/** The dispatch bodies, as the repositories received them. */
	final List<JsonNode> dispatches = Collections.synchronizedList(new ArrayList<>());

	/** Each answer the runs got from the exchange. */
	final List<Exchanged> exchanged = Collections.synchronizedList(new ArrayList<>());

	/** Every ID token the runs were given. */
	final List<String> idTokens = Collections.synchronizedList(new ArrayList<>());

	/** The Authorization header of each logs-storage request, {@code none} when there was none. */
	final List<String> logAuthorizations = Collections.synchronizedList(new ArrayList<>());

	final List<Long> deletedLogs = Collections.synchronizedList(new ArrayList<>());

	/** Pull request comments, as {@code owner/name#number}. */
	final List<String> comments = Collections.synchronizedList(new ArrayList<>());

	/** The runs cancelled through {@code POST …/actions/runs/{id}/cancel}. */
	final List<Long> cancelled = Collections.synchronizedList(new ArrayList<>());

	/** The runs of {@code vibefix.yml} dispatched with {@code operation=scan}. */
	final List<Long> scans = Collections.synchronizedList(new ArrayList<>());

	/** How many findings a scan's gitleaks reports: the run succeeds at 0 and fails above it. */
	volatile int scanFindings;

	/** A scan that fails before its count line, as one whose gitleaks download does not match its sha256. */
	volatile boolean scanLineMissing;

	/** A conclusion a scan ends with whatever its count, as a workflow that forgot to fail or failed late would. */
	volatile @Nullable String scanConclusion;

	/** How the count line shows in a scan's logs. */
	enum CountLines {

		/** Once in the whole job's log and once in the scan step's, with the same head and count. */
		ONCE,

		/** Twice, the same, in the scan step's log. */
		TWICE_IN_ONE_FILE,

		/** {@code findings=0} in the whole job's log and {@code findings=3} in the scan step's. */
		ZERO_AND_THREE

	}

	volatile CountLines countLines = CountLines.ONCE;

	/** How many reads of a scan's logs come before its count line is written, as while GitHub still writes them. */
	volatile int scanLogsWithoutLine;

	/** A {@code vibefix.yml} pushed on the default branch just before a scan starts, so the run reads that file. */
	volatile @Nullable String pushedBeforeScan;

	/** 200 with the run's ID when the dispatch asks for {@code return_run_details}; 204 for GitHub's old answer. */
	volatile int dispatchStatus = 200;

	/** How many reads of a run answer {@code queued} before it is {@code completed}. */
	volatile int pollsBeforeCompleted = 1;

	volatile boolean neverCompletes;

	/** A run fails at this step ({@code fetch}, {@code check}, {@code push}) after its exchange. */
	volatile @Nullable String failAtStep;

	/** Whether GitHub marks an open pull request merged once a merge brings its head commit in. */
	volatile boolean containedPullsMarkedMerged;

	/**
	 * Whether opening a pull request on a private repository needs a token that also reads contents, the 422
	 * {@code PullRequest.head invalid} answer otherwise: what the S6 run of 2026-11-11 suggested.
	 */
	volatile boolean pullsNeedContents;

	/** How many requests to open a pull request answer 422 first, whatever their token holds. */
	final AtomicLong pullsRefused = new AtomicLong();

	/** How many dispatches answer 404 first, as GitHub does for a workflow file it does not know yet. */
	volatile int dispatchNotFound;

	/** Whether a dispatch naming {@code return_run_details} is refused with 422, as an API without the field does. */
	volatile boolean refuseReturnRunDetails;

	/** What {@code DELETE /installation/token} answers. */
	volatile int revocationStatus = 204;

	/** What a ruleset on a private repository is answered with, status and message. */
	volatile int rulesetStatus = 403;

	volatile String rulesetMessage = FREE_RULESET;

	/** {@code /timing} without {@code run_duration_ms}, or without {@code billable}. */
	volatile boolean timingWithoutDuration;

	volatile boolean timingWithoutBillable;

	/** How the next runs' ID tokens are spoiled. */
	volatile Flaw flaw = Flaw.NONE;

	/** The organization's Actions policy: {@code none}, {@code all}, or {@code selected}. */
	volatile String actionsEnabled = "selected";

	/** The repositories {@code selected} lets run Actions, by name. */
	final List<String> actionsRepositories = Collections.synchronizedList(new ArrayList<>(List.of(RUNNER_NAME)));

	/** Whether the Platform App holds Actions write, as it must with a runner. */
	volatile boolean platformActions = true;

	/** Whether the Platform App's and the Source App's installations accepted Actions write; until then they lack it. */
	volatile boolean platformAccepted = true;

	volatile boolean sourceAccepted = true;

	/** The organization's {@code allowed_actions}. */
	volatile String actionsAllowed = "local_only";

	/** What {@code DELETE …/actions/runs/{id}/logs} answers. */
	volatile int logsDeleteStatus = 204;

	/** A repository whose branch reads ({@code GET …/git/ref/heads/…}) answer 502. */
	volatile @Nullable String refReadsFail;

	/** A value a failed step's log shows in clear, as a workflow that forgot to mask it would. */
	volatile @Nullable String logLeak;

	/** The issuer's clock: the probe's tests run at 2026-11-09T03:00:00Z. */
	volatile Instant now = Instant.parse("2026-11-09T03:00:00Z");

	private final RSAKey key = TestKeys.jwk(KEY_ID);

	private final HttpServer server;

	private final ExecutorService executor;

	private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

	private final AtomicLong ids = new AtomicLong(5000);

	private final AtomicLong commits = new AtomicLong();

	private final Map<String, Repo> repositories = Collections.synchronizedMap(new LinkedHashMap<>());

	/** Each commit's files, by path. */
	private final Map<String, Map<String, String>> trees = Collections.synchronizedMap(new LinkedHashMap<>());

	private final Map<Long, Run> runs = Collections.synchronizedMap(new LinkedHashMap<>());

	/** A repository: its branches as commit lists (head last), people, and pull requests. */
	static final class Repo {

		final long id;

		final String fullName;

		volatile boolean privateRepository;

		volatile String defaultBranch = "main";

		final Map<String, List<String>> branches = Collections.synchronizedMap(new LinkedHashMap<>());

		final Map<String, String> collaborators = Collections.synchronizedMap(new LinkedHashMap<>());

		final Map<String, String> invitations = Collections.synchronizedMap(new LinkedHashMap<>());

		final Map<Integer, Pull> pulls = Collections.synchronizedMap(new LinkedHashMap<>());

		Repo(long id, String fullName, boolean privateRepository) {
			this.id = id;
			this.fullName = fullName;
			this.privateRepository = privateRepository;
		}

	}

	static final class Pull {

		final int number;

		final String head;

		final String base;

		volatile String state = "open";

		volatile boolean merged;

		Pull(int number, String head, String base) {
			this.number = number;
			this.head = head;
			this.base = base;
		}

	}

	private static final class Run {

		final long id;

		final Repo repo;

		final String workflow;

		final String ref;

		final String headSha;

		final JsonNode inputs;

		/** Whether the workflow's "Exchange the ID token" step runs for these inputs, as its {@code if:} says. */
		final boolean exchanges;

		volatile String title;

		volatile String actor;

		volatile int polls;

		volatile boolean completed;

		volatile String conclusion = "success";

		volatile @Nullable String failedStep;

		/** A scan's count of findings, once its log holds the count line; else -1. */
		volatile int findings = -1;

		/** How often its logs were read. */
		final AtomicLong logReads = new AtomicLong();

		Run(long id, Repo repo, String workflow, String ref, String headSha, JsonNode inputs, boolean exchanges, String title,
				String actor) {
			this.id = id;
			this.repo = repo;
			this.workflow = workflow;
			this.ref = ref;
			this.headSha = headSha;
			this.inputs = inputs;
			this.exchanges = exchanges;
			this.title = title;
			this.actor = actor;
		}

		boolean customer() {
			return this.workflow.equals(SpikeS6Command.CUSTOMER_WORKFLOW);
		}

		boolean scan() {
			return customer() && "scan".equals(input(this, "operation"));
		}

	}

	/** @param customerWorkflow what the Customer added as {@code .github/workflows/vibefix.yml} */
	SpikeS6Stub(String customerWorkflow) throws IOException {
		Repo source = add(SOURCE, 9001, true);
		String first = commit(Map.of("README.md", "# Quán Mây\n"));
		String second = commit(Map.of("README.md", "# Quán Mây\n", CUSTOMER_PATH, customerWorkflow.replace("\r\n", "\n")));
		source.branches.put("main", new ArrayList<>(List.of(first, second)));
		source.collaborators.put(CUSTOMER, "admin");
		Repo runner = add(RUNNER, 9002, true);
		runner.branches.put("main", new ArrayList<>(List.of(commit(Map.of("README.md", "# vibefix-runner\n")))));
		this.executor = Executors.newCachedThreadPool(runnable -> {
			Thread thread = new Thread(runnable, "spike-s6-stub");
			thread.setDaemon(true);
			return thread;
		});
		this.server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		// A run's work asks the exchange, which reads the run back here: requests are served in parallel.
		this.server.setExecutor(this.executor);
		this.server.createContext("/", this::handle);
		this.server.start();
	}

	URI apiUrl() {
		return URI.create("http://127.0.0.1:" + this.server.getAddress().getPort());
	}

	/** The issuer the ID tokens name. */
	String issuer() {
		return apiUrl().toString();
	}

	URI jwks() {
		return URI.create(apiUrl() + "/.well-known/jwks");
	}

	@Override
	public void close() {
		this.server.stop(0);
		this.executor.shutdownNow();
	}

	Repo repository(String fullName) {
		return this.repositories.get(fullName.toLowerCase(Locale.ROOT));
	}

	@Nullable String head(String fullName, String branch) {
		Repo repo = repository(fullName);
		List<String> list = repo == null ? null : repo.branches.get(branch);
		return list == null || list.isEmpty() ? null : list.getLast();
	}

	/** A file at the head of a branch, or {@code null}. */
	@Nullable String file(String fullName, String branch, String path) {
		String head = head(fullName, branch);
		Map<String, String> tree = head == null ? null : this.trees.get(head);
		return tree == null ? null : tree.get(path);
	}

	/** A branch of the source, one commit past {@code main}, whose {@code vibefix.yml} is {@code content}. */
	void tamper(String branch, String content) {
		Repo source = repository(SOURCE);
		List<String> main = source.branches.get("main");
		Map<String, String> tree = new LinkedHashMap<>(this.trees.get(main.getLast()));
		tree.put(CUSTOMER_PATH, content);
		List<String> tampered = new ArrayList<>(main);
		tampered.add(commit(tree));
		source.branches.put(branch, tampered);
	}

	/** Commits one file on a branch, as a person would. */
	void commitFile(String fullName, String branch, String path, String content) {
		Repo repo = repository(fullName);
		List<String> list = repo.branches.computeIfAbsent(branch, name -> new ArrayList<>());
		Map<String, String> tree = new LinkedHashMap<>(list.isEmpty() ? Map.of() : this.trees.get(list.getLast()));
		tree.put(path, content);
		list.add(commit(tree));
	}

	/** The invitee accepts every pending invitation: a collaborator with the invitation's permission. */
	void accept(String login) {
		synchronized (this.repositories) {
			for (Repo repo : this.repositories.values()) {
				String permission = repo.invitations.remove(login);
				if (permission != null) {
					repo.collaborators.put(login, permission);
				}
			}
		}
	}

	/** Each run's ID, with the repository it ran in. */
	Map<Long, String> runRepositories() {
		Map<Long, String> found = new LinkedHashMap<>();
		synchronized (this.runs) {
			this.runs.forEach((id, run) -> found.put(id, run.repo.fullName));
		}
		return found;
	}

	private Repo add(String fullName, long id, boolean privateRepository) {
		Repo repo = new Repo(id, fullName, privateRepository);
		this.repositories.put(fullName.toLowerCase(Locale.ROOT), repo);
		return repo;
	}

	private String commit(Map<String, String> tree) {
		try {
			String sha = HexFormat.of()
				.formatHex(MessageDigest.getInstance("SHA-1")
					.digest(("commit-" + this.commits.incrementAndGet()).getBytes(StandardCharsets.UTF_8)));
			this.trees.put(sha, Map.copyOf(tree));
			return sha;
		}
		catch (GeneralSecurityException ex) {
			throw new IllegalStateException(ex);
		}
	}

	private void handle(HttpExchange exchange) throws IOException {
		String method = exchange.getRequestMethod();
		String path = exchange.getRequestURI().getPath();
		String query = exchange.getRequestURI().getQuery();
		byte[] raw = exchange.getRequestBody().readAllBytes();
		JsonNode body;
		try {
			body = raw.length == 0 ? GitHubHttp.JSON.createObjectNode() : GitHubHttp.JSON.readTree(raw);
		}
		catch (RuntimeException ex) {
			body = GitHubHttp.JSON.createObjectNode();
		}
		Seen request = new Seen(method, path, query, exchange.getRequestHeaders().getFirst("Authorization"), body);
		this.seen.add(request);
		try {
			route(exchange, request);
		}
		catch (Exception ex) {
			send(exchange, 500, Map.of("message", ex.getClass().getSimpleName()));
		}
	}

	private void route(HttpExchange exchange, Seen request) throws Exception {
		String method = request.method();
		String path = request.path();
		JsonNode body = request.body();
		Matcher matcher;
		if (method.equals("GET") && path.equals("/.well-known/jwks")) {
			byte[] bytes = new JWKSet(this.key.toPublicJWK()).toString().getBytes(StandardCharsets.UTF_8);
			exchange.getResponseHeaders().add("Content-Type", "application/json");
			exchange.sendResponseHeaders(200, bytes.length);
			try (OutputStream out = exchange.getResponseBody()) {
				out.write(bytes);
			}
			exchange.close();
		}
		else if (path.startsWith("/blob-logs/")) {
			this.logAuthorizations.add(request.authorization() == null ? "none" : request.authorization());
			Run run = this.runs.get(Long.parseLong(path.substring("/blob-logs/".length())));
			if (run == null || request.query() == null || !request.query().contains("sig=")) {
				send(exchange, 403, Map.of("message", "AuthenticationFailed"));
				return;
			}
			sendBytes(exchange, logs(run));
		}
		else if (method.equals("GET") && path.equals("/app")) {
			long app = issuer(request);
			send(exchange, app == 0 ? 401 : 200, app == 0 ? Map.of("message", "A JSON web token could not be decoded")
					: registration(app));
		}
		else if (method.equals("GET") && (matcher = match("/repos/([^/]+/[^/]+)/installation", path)) != null) {
			long app = issuer(request);
			Repo repo = repository(matcher.group(1));
			long installation = repo == null ? 0 : installationOf(app, repo);
			if (installation == 0) {
				send(exchange, 404, Map.of("message", "Not Found"));
				return;
			}
			send(exchange, 200, map("id", installation, "account", Map.of("login", repo.fullName.split("/")[0]),
					"repository_selection", installation == PLATFORM_INSTALLATION ? "all" : "selected", "permissions",
					ceiling(installation)));
		}
		else if (method.equals("GET") && (matcher = match("/app/installations/(\\d+)", path)) != null) {
			long installation = Long.parseLong(matcher.group(1));
			if (appOf(installation) == 0 || appOf(installation) != issuer(request)) {
				send(exchange, 404, Map.of("message", "Not Found"));
				return;
			}
			send(exchange, 200, map("id", installation, "repository_selection",
					installation == PLATFORM_INSTALLATION ? "all" : "selected", "permissions", ceiling(installation)));
		}
		else if (method.equals("POST") && (matcher = match("/app/installations/(\\d+)/access_tokens", path)) != null) {
			mint(exchange, request, Long.parseLong(matcher.group(1)));
		}
		else if (method.equals("DELETE") && path.equals("/installation/token")) {
			String token = bearer(request);
			if (this.revocationStatus != 204) {
				send(exchange, this.revocationStatus, Map.of("message", "Server Error"));
				return;
			}
			if (token == null || !this.grants.containsKey(token) || this.revoked.contains(token)) {
				send(exchange, 401, Map.of("message", "Bad credentials"));
				return;
			}
			this.revoked.add(token);
			this.events.add("revoked " + token);
			send(exchange, 204, null);
		}
		else if (method.equals("GET") && path.equals("/installation/repositories")) {
			Grant grant = grant(request);
			if (grant == null) {
				send(exchange, 401, Map.of("message", "Bad credentials"));
				return;
			}
			List<Map<String, Object>> listed = new ArrayList<>();
			synchronized (this.repositories) {
				for (Repo repo : this.repositories.values()) {
					if (installationCovers(grant.installation(), repo)
							&& (grant.repositoryIds().isEmpty() || grant.repositoryIds().contains(repo.id))) {
						listed.add(map("id", repo.id, "full_name", repo.fullName, "private", repo.privateRepository));
					}
				}
			}
			send(exchange, 200, map("total_count", listed.size(), "repositories", listed));
		}
		else if (method.equals("GET") && path.equals("/orgs/" + ORGANIZATION)) {
			if (refuse(exchange, request, null, "organization_administration", "read")) {
				return;
			}
			send(exchange, 200, map("login", ORGANIZATION, "two_factor_requirement_enabled", true,
					"default_repository_permission", "none", "members_can_fork_private_repositories", false));
		}
		else if (method.equals("GET") && path.equals("/orgs/" + ORGANIZATION + "/actions/permissions")) {
			if (refuse(exchange, request, null, "organization_administration", "read")) {
				return;
			}
			send(exchange, 200, map("enabled_repositories", this.actionsEnabled, "allowed_actions", this.actionsAllowed));
		}
		else if (method.equals("GET") && path.equals("/orgs/" + ORGANIZATION + "/actions/permissions/workflow")) {
			if (refuse(exchange, request, null, "organization_administration", "read")) {
				return;
			}
			send(exchange, 200, map("default_workflow_permissions", "read", "can_approve_pull_request_reviews", false));
		}
		else if (method.equals("GET") && path.equals("/orgs/" + ORGANIZATION + "/actions/permissions/repositories")) {
			if (refuse(exchange, request, null, "organization_administration", "read")) {
				return;
			}
			List<Map<String, Object>> listed = new ArrayList<>();
			List<String> names;
			synchronized (this.actionsRepositories) {
				names = List.copyOf(this.actionsRepositories);
			}
			for (String name : names) {
				Repo repo = repository(ORGANIZATION + "/" + name);
				listed.add(map("id", repo == null ? 0 : repo.id, "name", name, "full_name", ORGANIZATION + "/" + name));
			}
			send(exchange, 200, map("total_count", listed.size(), "repositories", listed));
		}
		else if (method.equals("POST") && path.equals("/orgs/" + ORGANIZATION + "/repos")) {
			Grant grant = grant(request);
			if (refuse(exchange, request, null, "administration", "write")) {
				return;
			}
			if (!grant.repositoryIds().isEmpty()) {
				send(exchange, 403, Map.of("message", "Resource not accessible by integration"));
				return;
			}
			String fullName = ORGANIZATION + "/" + body.path("name").asString();
			if (repository(fullName) != null) {
				send(exchange, 422, Map.of("message", "Repository creation failed: name already exists on this account"));
				return;
			}
			Repo repo = add(fullName, this.ids.incrementAndGet(), body.path("private").asBoolean(false));
			send(exchange, 201, describe(repo));
		}
		else if ((matcher = match("/repos/([^/]+/[^/]+)/(.+)", path)) != null && repository(matcher.group(1)) != null) {
			repositoryRoute(exchange, request, repository(matcher.group(1)), matcher.group(2));
		}
		else if (method.equals("GET") && (matcher = match("/repos/([^/]+/[^/]+)", path)) != null) {
			Repo repo = repository(matcher.group(1));
			if (repo == null || refuse(exchange, request, repo, "metadata", "read")) {
				if (repo == null) {
					send(exchange, 404, Map.of("message", "Not Found"));
				}
				return;
			}
			send(exchange, 200, describe(repo));
		}
		else {
			send(exchange, 404, Map.of("message", "Not Found", "path", path));
		}
	}

	private void mint(HttpExchange exchange, Seen request, long installation) throws IOException {
		if (appOf(installation) == 0 || appOf(installation) != issuer(request)) {
			send(exchange, 404, Map.of("message", "Not Found"));
			return;
		}
		Set<Long> ids = new HashSet<>();
		request.body().path("repository_ids").forEach(id -> ids.add(id.asLong()));
		for (long id : ids) {
			if (this.repositories.values().stream().noneMatch(repo -> repo.id == id && installationCovers(installation, repo))) {
				send(exchange, 422, Map.of("message",
						"There is at least one repository that does not exist or is not accessible to the parent installation."));
				return;
			}
		}
		Map<String, String> permissions = new LinkedHashMap<>();
		request.body().path("permissions").properties().forEach(entry -> permissions.put(entry.getKey(), entry.getValue().asString()));
		Map<String, String> ceiling = ceiling(installation);
		for (Map.Entry<String, String> wanted : permissions.entrySet()) {
			String granted = ceiling.get(wanted.getKey());
			if (granted == null || (wanted.getValue().equals("write") && !granted.equals("write"))) {
				send(exchange, 422, Map.of("message", "The permissions requested are not granted to this installation."));
				return;
			}
		}
		String token;
		synchronized (this.tokens) {
			// GitHub's stateless format since 2026: ghs_<App ID>_<JWT>, about 520 characters with dots and dashes.
			token = "ghs_" + installation + "_" + "eyJ" + "hbGciOiJSUzI1NiJ9." + "eyJ" + "zNnN0dWIi" + this.tokens.size()
					+ "A".repeat(400) + "." + "s6stub-" + "k3y_".repeat(20);
			this.tokens.add(token);
		}
		this.grants.put(token, new Grant(installation, Set.copyOf(ids), Map.copyOf(permissions)));
		send(exchange, 201, Map.of("token", token, "expires_at", "2026-11-09T04:00:00Z"));
	}

	private void repositoryRoute(HttpExchange exchange, Seen request, Repo repo, String rest) throws Exception {
		String method = request.method();
		JsonNode body = request.body();
		Matcher matcher;
		if (method.equals("GET") && (matcher = match("git/ref/heads/(.+)", rest)) != null) {
			if (refuse(exchange, request, repo, "contents", "read")) {
				return;
			}
			if (repo.fullName.equalsIgnoreCase(String.valueOf(this.refReadsFail))) {
				send(exchange, 502, Map.of("message", "Server Error"));
				return;
			}
			if (repo.branches.isEmpty()) {
				send(exchange, 409, Map.of("message", "Git Repository is empty."));
				return;
			}
			String head = head(repo.fullName, matcher.group(1));
			send(exchange, head == null ? 404 : 200, head == null ? Map.of("message", "Not Found")
					: map("ref", "refs/heads/" + matcher.group(1), "object", Map.of("sha", head, "type", "commit")));
		}
		else if (method.equals("POST") && rest.equals("git/refs")) {
			if (refuse(exchange, request, repo, "contents", "write")) {
				return;
			}
			String branch = body.path("ref").asString().replaceFirst("^refs/heads/", "");
			repo.branches.put(branch, new ArrayList<>(List.of(body.path("sha").asString())));
			send(exchange, 201, Map.of("ref", body.path("ref").asString()));
		}
		else if (method.equals("GET") && (matcher = match("collaborators/([^/]+)/permission", rest)) != null) {
			if (refuse(exchange, request, repo, "metadata", "read")) {
				return;
			}
			String permission = repo.collaborators.get(matcher.group(1));
			send(exchange, permission == null ? 404 : 200, permission == null
					? Map.of("message", matcher.group(1) + " is not a user")
					: Map.of("permission", permission, "role_name", permission, "user", Map.of("login", matcher.group(1))));
		}
		else if ((matcher = match("collaborators/([^/]+)", rest)) != null) {
			String login = matcher.group(1);
			if (method.equals("GET")) {
				if (refuse(exchange, request, repo, "metadata", "read")) {
					return;
				}
				send(exchange, repo.collaborators.containsKey(login) ? 204 : 404, null);
				return;
			}
			if (refuse(exchange, request, repo, "administration", "write")) {
				return;
			}
			if (repo.collaborators.containsKey(login)) {
				send(exchange, 204, null);
				return;
			}
			String permission = body.path("permission").asString("push").equals("pull") ? "read" : "write";
			repo.invitations.putIfAbsent(login, permission);
			send(exchange, 201, map("id", 77, "invitee", Map.of("login", login), "permissions", repo.invitations.get(login)));
		}
		else if (method.equals("GET") && rest.equals("invitations")) {
			if (refuse(exchange, request, repo, "metadata", "read")) {
				return;
			}
			List<Map<String, Object>> listed = new ArrayList<>();
			repo.invitations.forEach((login, permission) -> listed.add(map("id", 77, "invitee", Map.of("login", login),
					"permissions", permission)));
			send(exchange, 200, listed);
		}
		else if (method.equals("GET") && rest.equals("actions/permissions")) {
			if (refuse(exchange, request, repo, "administration", "read")) {
				return;
			}
			send(exchange, 200, Map.of("enabled", actionsAllowed(repo)));
		}
		else if (method.equals("POST") && rest.equals("rulesets")) {
			if (refuse(exchange, request, repo, "administration", "write")) {
				return;
			}
			send(exchange, repo.privateRepository ? this.rulesetStatus : 201, repo.privateRepository
					? Map.of("message", this.rulesetMessage) : Map.of("id", this.ids.incrementAndGet()));
		}
		else if ((matcher = match("contents/(.+)", rest)) != null) {
			contents(exchange, request, repo, matcher.group(1));
		}
		else if (rest.startsWith("actions/")) {
			actions(exchange, request, repo, rest.substring("actions/".length()));
		}
		else if (method.equals("POST") && rest.equals("pulls")) {
			if (refuse(exchange, request, repo, "pull_requests", "write")) {
				return;
			}
			String head = body.path("head").asString();
			String base = body.path("base").asString();
			String token = bearer(request);
			boolean blind = this.pullsNeedContents && repo.privateRepository
					&& (token == null || !allows(token, repo, "contents", "read"));
			if (this.pullsRefused.getAndUpdate(left -> Math.max(0, left - 1)) > 0 || blind) {
				send(exchange, 422, map("message", "Validation Failed", "errors",
						List.of(map("resource", "PullRequest", "field", "head", "code", "invalid")), "documentation_url",
						"https://docs.github.com/rest/pulls/pulls#create-a-pull-request"));
				return;
			}
			if (!repo.branches.containsKey(head) || !repo.branches.containsKey(base)) {
				send(exchange, 422, Map.of("message", "Validation Failed"));
				return;
			}
			int number = repo.pulls.size() + 1;
			repo.pulls.put(number, new Pull(number, head, base));
			send(exchange, 201, Map.of("number", number));
		}
		else if (method.equals("PUT") && (matcher = match("pulls/(\\d+)/merge", rest)) != null) {
			if (refuse(exchange, request, repo, "contents", "write")) {
				return;
			}
			Pull pull = repo.pulls.get(Integer.parseInt(matcher.group(1)));
			if (pull == null || pull.merged) {
				send(exchange, 405, Map.of("message", "Pull Request is not mergeable"));
				return;
			}
			List<String> head = repo.branches.get(pull.head);
			if (!head.getLast().equals(body.path("sha").asString())) {
				send(exchange, 409, Map.of("message", "Head branch was modified. Review and try the merge again."));
				return;
			}
			List<String> base = repo.branches.get(pull.base);
			for (String commit : head) {
				if (!base.contains(commit)) {
					base.add(commit);
				}
			}
			String merge = commit(this.trees.getOrDefault(head.getLast(), Map.of()));
			base.add(merge);
			pull.merged = true;
			pull.state = "closed";
			if (this.containedPullsMarkedMerged) {
				for (Pull other : repo.pulls.values()) {
					if ("open".equals(other.state) && base.contains(repo.branches.get(other.head).getLast())) {
						other.merged = true;
						other.state = "closed";
					}
				}
			}
			send(exchange, 200, Map.of("sha", merge, "merged", true, "message", "Pull Request successfully merged"));
		}
		else if ((matcher = match("pulls/(\\d+)", rest)) != null) {
			Pull pull = repo.pulls.get(Integer.parseInt(matcher.group(1)));
			if (pull == null) {
				send(exchange, 404, Map.of("message", "Not Found"));
				return;
			}
			if (method.equals("PATCH")) {
				if (refuse(exchange, request, repo, "pull_requests", "write")) {
					return;
				}
				pull.state = body.path("state").asString(pull.state);
			}
			else if (refuse(exchange, request, repo, "metadata", "read")) {
				return;
			}
			send(exchange, 200, Map.of("number", pull.number, "state", pull.state, "merged", pull.merged));
		}
		else if (method.equals("POST") && (matcher = match("issues/(\\d+)/comments", rest)) != null) {
			if (refuse(exchange, request, repo, "pull_requests", "write")) {
				return;
			}
			this.comments.add(repo.fullName + "#" + matcher.group(1));
			send(exchange, 201, Map.of("id", this.ids.incrementAndGet()));
		}
		else {
			send(exchange, 404, Map.of("message", "Not Found", "path", rest));
		}
	}

	/** {@code GET} a file or a folder at {@code ?ref=} (a branch or a commit), or {@code PUT} a file on a branch. */
	private void contents(HttpExchange exchange, Seen request, Repo repo, String file) throws IOException {
		if (request.method().equals("GET")) {
			if (refuse(exchange, request, repo, "contents", "read")) {
				return;
			}
			String ref = parameter(request.query(), "ref");
			Map<String, String> tree = tree(repo, ref == null ? repo.defaultBranch : ref);
			if (tree == null) {
				send(exchange, 404, Map.of("message", "No commit found for the ref " + ref));
				return;
			}
			String content = tree.get(file);
			if (content == null) {
				// A folder lists its files, as GitHub answers with an array.
				List<Map<String, Object>> listed = new ArrayList<>();
				for (String path : new TreeSet<>(tree.keySet())) {
					if (path.startsWith(file + "/") && !path.substring(file.length() + 1).contains("/")) {
						listed.add(map("name", path.substring(file.length() + 1), "path", path, "type", "file"));
					}
				}
				send(exchange, listed.isEmpty() ? 404 : 200, listed.isEmpty() ? Map.of("message", "Not Found") : listed);
				return;
			}
			send(exchange, 200, Map.of("path", file, "sha", blobSha(content), "encoding", "base64", "content",
					Base64.getMimeEncoder().encodeToString(content.getBytes(StandardCharsets.UTF_8))));
			return;
		}
		boolean workflow = file.startsWith(".github/workflows/");
		if (refuse(exchange, request, repo, "contents", "write") || (workflow && refuse(exchange, request, repo, "workflows", "write"))) {
			return;
		}
		String branch = request.body().path("branch").asString(repo.defaultBranch);
		List<String> list = repo.branches.computeIfAbsent(branch, name -> new ArrayList<>());
		String parent = list.isEmpty() ? null : list.getLast();
		Map<String, String> tree = new LinkedHashMap<>(parent == null ? Map.of() : this.trees.get(parent));
		String existing = tree.get(file);
		if (existing != null && !blobSha(existing).equals(request.body().path("sha").asString(""))) {
			send(exchange, 422, Map.of("message", "Invalid request.\n\n\"sha\" wasn't supplied."));
			return;
		}
		String content = new String(Base64.getMimeDecoder().decode(request.body().path("content").asString("")), StandardCharsets.UTF_8);
		tree.put(file, content);
		String commit = commit(tree);
		list.add(commit);
		send(exchange, existing == null ? 201 : 200, map("content", Map.of("path", file, "sha", blobSha(content)), "commit",
				map("sha", commit, "parents", parent == null ? List.of() : List.of(Map.of("sha", parent)))));
	}

	private @Nullable Map<String, String> tree(Repo repo, String ref) {
		if (ref.matches("[0-9a-f]{40}")) {
			return this.trees.get(ref);
		}
		List<String> branch = repo.branches.get(ref);
		return branch == null || branch.isEmpty() ? null : this.trees.get(branch.getLast());
	}

	private void actions(HttpExchange exchange, Seen request, Repo repo, String rest) throws Exception {
		String method = request.method();
		Matcher matcher;
		if (method.equals("POST") && (matcher = match("workflows/([^/]+)/dispatches", rest)) != null) {
			dispatch(exchange, request, repo, matcher.group(1));
		}
		else if (method.equals("GET") && (matcher = match("workflows/([^/]+)/runs", rest)) != null) {
			if (refuse(exchange, request, repo, "actions", "read")) {
				return;
			}
			String workflow = matcher.group(1);
			List<Map<String, Object>> listed = new ArrayList<>();
			synchronized (this.runs) {
				for (Run run : this.runs.values()) {
					if (run.repo == repo && run.workflow.equals(workflow)) {
						listed.add(0, map("id", run.id, "name", workflow.replace(".yml", ""), "display_title", run.title,
								"event", "workflow_dispatch", "status", status(run, false)));
					}
				}
			}
			send(exchange, 200, map("total_count", listed.size(), "workflow_runs", listed));
		}
		else if (method.equals("GET") && (matcher = match("runs/(\\d+)", rest)) != null) {
			if (refuse(exchange, request, repo, "actions", "read")) {
				return;
			}
			Run run = run(repo, matcher.group(1));
			if (run == null) {
				send(exchange, 404, Map.of("message", "Not Found"));
				return;
			}
			String status = status(run, true);
			send(exchange, 200, map("id", run.id, "name", run.workflow.replace(".yml", ""), "display_title", run.title, "path",
					".github/workflows/" + run.workflow, "head_branch", run.ref, "head_sha", run.headSha, "event",
					"workflow_dispatch", "triggering_actor", Map.of("login", run.actor), "actor", Map.of("login", run.actor),
					"status", status, "conclusion", status.equals("completed") ? run.conclusion : null, "html_url",
					"https://github.com/" + repo.fullName + "/actions/runs/" + run.id, "run_started_at", "2026-11-09T03:00:00Z",
					"updated_at", "2026-11-09T03:00:42Z"));
		}
		else if (method.equals("POST") && (matcher = match("runs/(\\d+)/cancel", rest)) != null) {
			if (refuse(exchange, request, repo, "actions", "write")) {
				return;
			}
			this.cancelled.add(Long.parseLong(matcher.group(1)));
			send(exchange, 202, Map.of());
		}
		else if (method.equals("GET") && (matcher = match("runs/(\\d+)/jobs", rest)) != null) {
			if (refuse(exchange, request, repo, "actions", "read")) {
				return;
			}
			Run run = run(repo, matcher.group(1));
			if (run == null) {
				send(exchange, 404, Map.of("message", "Not Found"));
				return;
			}
			List<String> names = steps(run);
			int failing = failingStep(run);
			List<Map<String, Object>> steps = new ArrayList<>();
			for (int i = 0; i < names.size(); i++) {
				int number = i + 1;
				steps.add(map("number", number, "name", names.get(i), "conclusion", failing == 0 || number < failing ? "success"
						: number == failing ? "failure" : number < names.size() - 1 ? "skipped" : "success"));
			}
			// The job takes 65 seconds: Free bills it as two minutes. A scan's run also lists the copy job, skipped.
			List<Map<String, Object>> jobs = new ArrayList<>();
			if (job(run).equals("scan")) {
				jobs.add(map("id", run.id + 2, "name", "vibefix", "conclusion", "skipped", "steps", List.of()));
			}
			jobs.add(map("id", run.id + 1, "name", job(run), "started_at", "2026-11-09T03:00:00Z", "completed_at",
					"2026-11-09T03:01:05Z", "conclusion", run.conclusion, "steps", steps));
			send(exchange, 200, map("total_count", jobs.size(), "jobs", jobs));
		}
		else if (method.equals("GET") && (matcher = match("runs/(\\d+)/logs", rest)) != null) {
			if (refuse(exchange, request, repo, "actions", "read")) {
				return;
			}
			exchange.getResponseHeaders().add("Location", apiUrl() + "/blob-logs/" + matcher.group(1) + "?sv=2026-11-09&sig=stub"
					+ "signature");
			exchange.sendResponseHeaders(302, -1);
			exchange.close();
		}
		else if (method.equals("GET") && (matcher = match("runs/(\\d+)/timing", rest)) != null) {
			if (refuse(exchange, request, repo, "actions", "read")) {
				return;
			}
			long duration = 10_000 + Long.parseLong(matcher.group(1)) % 1000;
			Map<String, Object> timing = new LinkedHashMap<>();
			timing.put("billable", this.timingWithoutBillable ? Map.of() : Map.of("UBUNTU", map("total_ms", duration, "jobs", 1,
					"job_runs", List.of(Map.of("job_id", 1, "duration_ms", duration)))));
			if (!this.timingWithoutDuration) {
				timing.put("run_duration_ms", duration);
			}
			send(exchange, 200, timing);
		}
		else if (method.equals("DELETE") && (matcher = match("runs/(\\d+)/logs", rest)) != null) {
			if (refuse(exchange, request, repo, "actions", "write")) {
				return;
			}
			if (this.logsDeleteStatus != 204) {
				send(exchange, this.logsDeleteStatus, Map.of("message", "Resource not accessible by integration"));
				return;
			}
			this.deletedLogs.add(Long.parseLong(matcher.group(1)));
			send(exchange, 204, null);
		}
		else {
			send(exchange, 404, Map.of("message", "Not Found", "path", rest));
		}
	}

	private void dispatch(HttpExchange exchange, Seen request, Repo repo, String workflow) throws Exception {
		if (refuse(exchange, request, repo, "actions", "write")) {
			return;
		}
		String ref = request.body().path("ref").asString("");
		List<String> branch = repo.branches.get(ref);
		if (branch == null || branch.isEmpty()) {
			send(exchange, 422, Map.of("message", "No ref found for: " + ref));
			return;
		}
		String pushed = this.pushedBeforeScan;
		if (pushed != null && workflow.equals(SpikeS6Command.CUSTOMER_WORKFLOW)
				&& "scan".equals(request.body().path("inputs").path("operation").asString(""))) {
			// Someone pushes a changed vibefix.yml just before the scan starts: the run reads that one.
			commitFile(repo.fullName, ref, CUSTOMER_PATH, pushed);
		}
		String file = this.trees.get(branch.getLast()).get(".github/workflows/" + workflow);
		if (file == null || this.dispatchNotFound > 0) {
			this.dispatchNotFound = Math.max(0, this.dispatchNotFound - 1);
			send(exchange, 404, Map.of("message", "Not Found"));
			return;
		}
		if (!actionsAllowed(repo)) {
			send(exchange, 403, Map.of("message", "Actions has been disabled for this repository."));
			return;
		}
		if (this.refuseReturnRunDetails && request.body().has("return_run_details")) {
			send(exchange, 422, Map.of("message", "Invalid request.\n\n\"return_run_details\" is not a permitted key."));
			return;
		}
		JsonNode inputs = request.body().path("inputs");
		Map<String, Object> workflowFile = yaml(file);
		String refused = unexpectedInputs(workflowFile, inputs);
		if (refused != null) {
			send(exchange, 422, Map.of("message", refused));
			return;
		}
		this.dispatches.add(request.body());
		String actor = slug(grant(request).installation()) + "[bot]";
		Run run = new Run(this.ids.incrementAndGet(), repo, workflow, ref, branch.getLast(), inputs,
				exchangeRuns(workflowFile, inputs), runName(workflowFile, inputs), actor);
		this.runs.put(run.id, run);
		work(run);
		if (this.dispatchStatus == 200 && request.body().path("return_run_details").asBoolean(false)) {
			send(exchange, 200, Map.of("workflow_run_id", run.id, "run_url", apiUrl() + "/repos/" + repo.fullName
					+ "/actions/runs/" + run.id, "html_url", "https://github.com/" + repo.fullName + "/actions/runs/" + run.id));
		}
		else {
			send(exchange, 204, null);
		}
	}

	/** What the published workflow does: its ID token exchanged, then only what the token reaches copied. */
	private void work(Run run) {
		if (run.scan()) {
			this.scans.add(run.id);
		}
		Flaw flaw = this.flaw;
		if (flaw == Flaw.FOREIGN_ACTOR) {
			run.actor = "mallory";
		}
		if (flaw == Flaw.FOREIGN_TITLE) {
			run.title = run.workflow.replace(".yml", "") + " without its correlation";
		}
		if (!run.exchanges) {
			// The workflow skips its exchange: the run asks for no ID token and holds no vibefix token.
			if (run.scan()) {
				scan(run);
			}
			else {
				fail(run, "push");
			}
			return;
		}
		String idToken = idToken(run, flaw);
		this.idTokens.add(idToken);
		String correlation = flaw == Flaw.UNKNOWN_CORRELATION ? "copy-00000000deadbeef" : input(run, "correlation");
		Exchanged answer = exchange(run, idToken, correlation);
		this.exchanged.add(answer);
		if (flaw == Flaw.REPLAY && answer.status() == 200) {
			this.exchanged.add(exchange(run, idToken, correlation));
		}
		if (answer.status() != 200 || answer.token() == null) {
			fail(run, "exchange");
			return;
		}
		String failAt = this.failAtStep;
		if (failAt != null) {
			fail(run, failAt);
			return;
		}
		String step = run.customer() ? customer(run, answer.token()) : copy(run, answer.token());
		if (step != null) {
			fail(run, step);
		}
	}

	/** {@code vibefix.yml}: the step a run stopped at, or {@code null} when it copied. */
	private @Nullable String customer(Run run, String token) {
		String sha = input(run, "sha");
		String branch = input(run, "branch");
		Repo intermediary = repository(input(run, "intermediary"));
		if ("copy".equals(input(run, "operation"))) {
			// Its own repository, read with its GITHUB_TOKEN.
			List<String> commits = run.repo.branches.get(run.ref);
			if (commits == null || !commits.contains(sha)) {
				return "check";
			}
			List<String> copied = new ArrayList<>(commits.subList(0, commits.indexOf(sha) + 1));
			Repo working = repository(input(run, "working"));
			for (Repo target : new Repo[] { intermediary, working }) {
				if (target == null || !allows(token, target, "contents", "write") || !fastForward(target, branch, copied)) {
					return "push";
				}
				push(target, branch, copied);
			}
			return null;
		}
		if (intermediary == null || !allows(token, intermediary, "contents", "read")) {
			return "fetch";
		}
		List<String> commits = intermediary.branches.get(intermediary.defaultBranch);
		if (commits == null || !commits.contains(sha)) {
			return "check";
		}
		if (run.repo.branches.containsKey(branch)) {
			return "push";
		}
		// The new branch here, pushed with its GITHUB_TOKEN.
		run.repo.branches.put(branch, new ArrayList<>(commits.subList(0, commits.indexOf(sha) + 1)));
		return null;
	}

	/**
	 * {@code vibefix.yml}'s scan, on its own repository with its {@code GITHUB_TOKEN}: without its count
	 * line, it fails at the download; with findings, it fails at the scan after the count line.
	 */
	private void scan(Run run) {
		if (this.scanLineMissing) {
			fail(run, "download");
		}
		else {
			run.findings = this.scanFindings;
			if (run.findings > 0) {
				fail(run, "scan");
			}
		}
		String conclusion = this.scanConclusion;
		if (conclusion != null) {
			run.conclusion = conclusion;
			if (conclusion.equals("success")) {
				run.failedStep = null;
			}
			else if (run.failedStep == null) {
				run.failedStep = "scan";
			}
		}
	}

	/** {@code s6-copy.yml}: the step a run stopped at, or {@code null} when it copied. */
	private @Nullable String copy(Run run, String token) {
		Repo source = repository(input(run, "source_repository"));
		Repo target = repository(input(run, "target_repository"));
		List<String> commits = source == null ? null : source.branches.get(input(run, "source_branch"));
		String sha = input(run, "sha");
		String branch = input(run, "target_branch");
		if (commits == null || !allows(token, source, "contents", "read")) {
			return "fetch";
		}
		if (!commits.contains(sha)) {
			return "check";
		}
		List<String> copied = new ArrayList<>(commits.subList(0, commits.indexOf(sha) + 1));
		if (target == null || !allows(token, target, "contents", "write") || !fastForward(target, branch, copied)) {
			return "push";
		}
		push(target, branch, copied);
		return null;
	}

	/** A push without force: a new branch, or one whose head the pushed history holds. */
	private static boolean fastForward(Repo target, String branch, List<String> commits) {
		List<String> existing = target.branches.get(branch);
		return existing == null || existing.isEmpty() || commits.contains(existing.getLast());
	}

	private static void push(Repo target, String branch, List<String> commits) {
		if (target.branches.isEmpty()) {
			target.defaultBranch = branch;
		}
		target.branches.put(branch, new ArrayList<>(commits));
	}

	private static void fail(Run run, String step) {
		run.conclusion = "failure";
		run.failedStep = step;
	}

	/** The run's ID token, as GitHub Actions signs it for {@code audience=vibefix}, spoiled as {@code flaw} says. */
	private String idToken(Run run, Flaw flaw) {
		Instant now = this.now;
		String ref = "refs/heads/" + run.ref;
		String workflowRef = run.repo.fullName + "/.github/workflows/" + run.workflow + "@" + ref;
		JWTClaimsSet claims = new JWTClaimsSet.Builder().issuer(flaw == Flaw.ISSUER ? "https://token.example.test" : issuer())
			.audience(flaw == Flaw.AUDIENCE ? "sts.amazonaws.com" : OidcExchange.AUDIENCE)
			.subject("repo:" + run.repo.fullName + ":ref:" + ref)
			.jwtID(UUID.randomUUID().toString())
			.issueTime(Date.from(flaw == Flaw.EXPIRED ? now.minus(Duration.ofMinutes(20)) : now.minusSeconds(5)))
			.notBeforeTime(Date.from(flaw == Flaw.EXPIRED ? now.minus(Duration.ofMinutes(20)) : now.minusSeconds(5)))
			.expirationTime(Date.from(flaw == Flaw.EXPIRED ? now.minus(Duration.ofMinutes(10)) : now.plus(Duration.ofMinutes(5))))
			.claim("repository", run.repo.fullName)
			.claim("repository_id", String.valueOf(run.repo.id))
			.claim("repository_owner", run.repo.fullName.split("/")[0])
			.claim("ref", ref)
			.claim("ref_type", "branch")
			.claim("sha", run.headSha)
			.claim("workflow_sha", run.headSha)
			.claim("workflow_ref", workflowRef)
			.claim("job_workflow_ref", workflowRef)
			.claim("event_name", "workflow_dispatch")
			.claim("run_id", String.valueOf(run.id))
			.claim("run_attempt", "1")
			.claim("runner_environment", flaw == Flaw.SELF_HOSTED ? "self-hosted" : "github-hosted")
			.claim("actor", run.actor)
			.build();
		SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(KEY_ID).type(JOSEObjectType.JWT).build(),
				claims);
		try {
			jwt.sign(new RSASSASigner(flaw == Flaw.SIGNATURE ? TestKeys.foreignJwk(KEY_ID) : this.key));
		}
		catch (JOSEException ex) {
			throw new IllegalStateException(ex);
		}
		return jwt.serialize();
	}

	/** The published workflow's {@code POST $EXCHANGE_URL/github/actions/exchange}, which the probe serves too. */
	private Exchanged exchange(Run run, String idToken, String correlation) {
		String url = input(run, "exchange_url") + OidcExchange.PRODUCTION_PATH;
		try {
			HttpResponse<String> response = this.client.send(HttpRequest.newBuilder(URI.create(url))
				.timeout(Duration.ofSeconds(30))
				.header("Content-Type", "application/json")
				.POST(HttpRequest.BodyPublishers
					.ofString(GitHubHttp.JSON.writeValueAsString(Map.of("id_token", idToken, "correlation", correlation))))
				.build(), HttpResponse.BodyHandlers.ofString());
			JsonNode answer = GitHubHttp.JSON.readTree(response.body());
			return new Exchanged(run.id, correlation, response.statusCode(),
					answer.path("reason").isString() ? answer.path("reason").stringValue() : null,
					answer.path("token").isString() ? answer.path("token").stringValue() : null);
		}
		catch (IOException | RuntimeException ex) {
			return new Exchanged(run.id, correlation, 0, "unreachable", null);
		}
		catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
			return new Exchanged(run.id, correlation, 0, "interrupted", null);
		}
	}

	private static String input(Run run, String name) {
		return run.inputs.path(name).asString("");
	}

	/** The job a run's work happens in: {@code vibefix.yml}'s scan job for a scan that exchanges nothing. */
	private static String job(Run run) {
		if (!run.customer()) {
			return "copy";
		}
		return run.scan() && !run.exchanges ? "scan" : "vibefix";
	}

	private static List<String> steps(Run run) {
		return switch (job(run)) {
			case "vibefix" -> List.of("Set up job", "Check the inputs", "Exchange the ID token", "Copy the commit",
					"Bring the Final Merge back", "Remove the credentials", "Complete job");
			case "scan" -> List.of("Set up job", "Check the inputs", "Download gitleaks", "Scan with gitleaks",
					"Remove the credentials", "Complete job");
			default -> List.of("Set up job", "Check the inputs", "Exchange the ID token", "Copy the commit",
					"Remove the credentials", "Complete job");
		};
	}

	/** The failed step's number, or 0 for a run that succeeded. */
	private static int failingStep(Run run) {
		String failed = run.failedStep;
		if (failed == null) {
			return 0;
		}
		return switch (failed) {
			case "exchange" -> 3;
			case "download" -> 3;
			case "scan" -> 4;
			default -> run.customer() && "final-merge".equals(input(run, "operation")) ? 5 : 4;
		};
	}

	/** Whether the workflow's "Exchange the ID token" step runs for these inputs: its job's {@code if:} and its own. */
	@SuppressWarnings("unchecked")
	private static boolean exchangeRuns(Map<String, Object> workflow, JsonNode inputs) {
		for (Object job : ((Map<String, Object>) workflow.get("jobs")).values()) {
			Map<String, Object> declared = (Map<String, Object>) job;
			for (Map<String, Object> step : (List<Map<String, Object>>) declared.get("steps")) {
				if ("Exchange the ID token".equals(step.get("name"))) {
					return runs(declared.get("if"), inputs) && runs(step.get("if"), inputs);
				}
			}
		}
		throw new IllegalStateException("The workflow has no step named Exchange the ID token");
	}

	/**
	 * A job's or a step's {@code if:} as the published workflows write it: none, or comparisons of
	 * {@code inputs.operation} with a quoted name, joined by {@code ||}. Anything else stops the stand-in.
	 */
	private static boolean runs(@Nullable Object condition, JsonNode inputs) {
		if (condition == null) {
			return true;
		}
		String operation = inputs.path("operation").asString("");
		Pattern comparison = Pattern.compile("\\s*inputs\\.operation\\s*(==|!=)\\s*'([a-z-]+)'\\s*");
		for (String part : String.valueOf(condition).split("\\|\\|")) {
			Matcher matcher = comparison.matcher(part);
			if (!matcher.matches()) {
				throw new IllegalStateException("An if: the stand-in cannot read: " + condition);
			}
			if (matcher.group(1).equals("==") == operation.equals(matcher.group(2))) {
				return true;
			}
		}
		return false;
	}

	/** Why GitHub would refuse these inputs: a key the workflow does not declare, a required one missing, or a choice not offered. */
	@SuppressWarnings("unchecked")
	private static @Nullable String unexpectedInputs(Map<String, Object> workflow, JsonNode inputs) {
		Object on = workflow.containsKey("on") ? workflow.get("on") : workflow.get(Boolean.TRUE);
		Map<String, Map<String, Object>> declared = (Map<String, Map<String, Object>>) ((Map<String, Object>) ((Map<String, Object>) on)
			.get("workflow_dispatch")).get("inputs");
		List<String> unexpected = new ArrayList<>();
		inputs.properties().forEach(input -> {
			if (!declared.containsKey(input.getKey())) {
				unexpected.add(input.getKey());
			}
		});
		if (!unexpected.isEmpty()) {
			return "Unexpected inputs provided: " + unexpected;
		}
		for (Map.Entry<String, Map<String, Object>> input : declared.entrySet()) {
			if (Boolean.TRUE.equals(input.getValue().get("required")) && !inputs.has(input.getKey())) {
				return "Required input '" + input.getKey() + "' not provided";
			}
			if ("choice".equals(input.getValue().get("type")) && inputs.has(input.getKey())
					&& !((List<Object>) input.getValue().get("options")).contains(inputs.path(input.getKey()).asString(""))) {
				return "Provided value for input '" + input.getKey() + "' is not one of its options";
			}
		}
		return null;
	}

	/** The workflow's {@code run-name} with its inputs put in, as GitHub shows a run's title. */
	private static String runName(Map<String, Object> workflow, JsonNode inputs) {
		Matcher input = Pattern.compile("\\$\\{\\{\\s*inputs\\.([a-z_]+)\\s*\\}\\}").matcher(String.valueOf(workflow.get("run-name")));
		StringBuilder title = new StringBuilder();
		while (input.find()) {
			input.appendReplacement(title, Matcher.quoteReplacement(inputs.path(input.group(1)).asString("")));
		}
		input.appendTail(title);
		return title.toString();
	}

	@SuppressWarnings("unchecked")
	private static Map<String, Object> yaml(String file) {
		return (Map<String, Object>) new Yaml(new SafeConstructor(new LoaderOptions())).load(file);
	}

	/**
	 * The run's logs as GitHub zips them: the whole job's log at the top, and one file per step under the
	 * job's folder. The failed step's log has {@value #LOG_LINES} lines, a masked token among them.
	 */
	private byte[] logs(Run run) throws IOException {
		if (run.scan()) {
			return scanLogs(run);
		}
		String job = run.customer() ? "vibefix" : "copy";
		int failing = failingStep(run) == 0 ? 4 : failingStep(run);
		String leak = this.logLeak;
		StringBuilder step = new StringBuilder();
		for (int line = 1; line <= LOG_LINES; line++) {
			step.append("2026-11-09T03:00:").append(String.format(Locale.ROOT, "%02d", line % 60)).append(".0000000Z ")
				.append(line == LOG_LINES - 2 && leak != null ? "remote: credential " + leak + " refused"
						: line == LOG_LINES - 1 ? "fatal: could not push with token ***"
								: line == LOG_LINES ? "error: failed to push some refs (log line " + line + ")" : "log line " + line)
				.append('\n');
		}
		ByteArrayOutputStream bytes = new ByteArrayOutputStream();
		try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
			zip.putNextEntry(new ZipEntry("0_" + job + ".txt"));
			zip.write(("the whole job's log\n" + step).getBytes(StandardCharsets.UTF_8));
			zip.closeEntry();
			zip.putNextEntry(new ZipEntry(job + "/1_Set up job.txt"));
			zip.write("Set up job\n".getBytes(StandardCharsets.UTF_8));
			zip.closeEntry();
			zip.putNextEntry(new ZipEntry(job + "/" + failing + "_" + steps(run).get(failing - 1) + ".txt"));
			zip.write(step.toString().getBytes(StandardCharsets.UTF_8));
			zip.closeEntry();
		}
		return bytes.toByteArray();
	}

	/**
	 * A scan's logs: the whole job's log and the last step it ran, both holding that step's lines, as
	 * {@link #countLines} says. The scan step shows lines no count may be read from (the script's own
	 * echo, one without GitHub's timestamp, and one behind a bare CR), a line naming a finding's file and
	 * rule (as a workflow printing gitleaks' own output would, so the tests can see the probe prints none
	 * of it), and the count line with the run's head, once the first {@link #scanLogsWithoutLine} reads are
	 * past. A scan without its count line stops at the download.
	 */
	private byte[] scanLogs(Run run) throws IOException {
		String job = job(run);
		int last = failingStep(run) == 0 ? 4 : failingStep(run);
		String at = "2026-11-09T03:00:41.0000000Z ";
		String escape = String.valueOf((char) 0x1B);
		String count = SpikeS6Command.COUNT_PREFIX + " head=" + run.headSha + " findings=";
		boolean written = run.logReads.incrementAndGet() > this.scanLogsWithoutLine;
		CountLines lines = this.countLines;
		StringBuilder step = new StringBuilder(String.valueOf((char) 0xFEFF)).append(at)
			.append("##[group]Run set -euo pipefail\n")
			.append(at)
			.append(escape)
			.append("[36;1mecho \"")
			.append(SpikeS6Command.COUNT_PREFIX)
			.append(" head=$head findings=$count\"")
			.append(escape)
			.append("[0m\n")
			.append(at)
			.append("##[endgroup]\n")
			.append(count)
			.append("98\n")
			.append(at)
			.append("progress\r")
			.append(at)
			.append(count)
			.append("99\n");
		if (run.findings >= 0 && written) {
			if (run.findings > 0) {
				step.append(at).append("Finding: ").append(FINDING_RULE).append(" in ").append(FINDING_FILE).append('\n');
			}
			step.append(at).append(count).append(run.findings).append('\n');
			if (lines == CountLines.TWICE_IN_ONE_FILE) {
				step.append(at).append(count).append(run.findings).append('\n');
			}
			if (run.findings > 0) {
				step.append(at).append("##[error]gitleaks found ").append(run.findings).append(" finding(s)\n");
			}
		}
		else if ("download".equals(run.failedStep)) {
			step.append(at).append("##[error]the gitleaks download does not match its pinned sha256\n");
		}
		else if (run.findings < 0) {
			step.append(at).append("##[error]the run stopped before its scan\n");
		}
		String whole = lines == CountLines.ZERO_AND_THREE
				? step.toString().replace(at + count + run.findings + "\n", at + count + "0\n") : step.toString();
		ByteArrayOutputStream bytes = new ByteArrayOutputStream();
		try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
			zip.putNextEntry(new ZipEntry("0_" + job + ".txt"));
			zip.write(("the whole job's log\n" + whole).getBytes(StandardCharsets.UTF_8));
			zip.closeEntry();
			zip.putNextEntry(new ZipEntry(job + "/1_Set up job.txt"));
			zip.write("Set up job\n".getBytes(StandardCharsets.UTF_8));
			zip.closeEntry();
			zip.putNextEntry(new ZipEntry(job + "/" + last + "_" + steps(run).get(last - 1) + ".txt"));
			zip.write(step.toString().getBytes(StandardCharsets.UTF_8));
			zip.closeEntry();
		}
		return bytes.toByteArray();
	}

	private String status(Run run, boolean poll) {
		if (this.neverCompletes) {
			return "in_progress";
		}
		if (poll && !run.completed && ++run.polls > this.pollsBeforeCompleted) {
			run.completed = true;
			this.events.add("completed " + run.id);
		}
		return run.completed ? "completed" : "queued";
	}

	private @Nullable Run run(Repo repo, String id) {
		Run run = this.runs.get(Long.parseLong(id));
		return run == null || run.repo != repo ? null : run;
	}

	/** Whether the organization's policy lets the repository run Actions; one outside the organization may. */
	private boolean actionsAllowed(Repo repo) {
		if (!repo.fullName.startsWith(ORGANIZATION + "/")) {
			return true;
		}
		return switch (this.actionsEnabled) {
			case "all" -> true;
			case "selected" -> this.actionsRepositories.stream().anyMatch(name -> repo.fullName.equalsIgnoreCase(ORGANIZATION + "/" + name));
			default -> false;
		};
	}

	private boolean allows(String token, Repo repo, String permission, String level) {
		Grant grant = this.grants.get(token);
		if (grant == null || this.revoked.contains(token) || !installationCovers(grant.installation(), repo)
				|| (!grant.repositoryIds().isEmpty() && !grant.repositoryIds().contains(repo.id))) {
			return false;
		}
		if (permission.equals("metadata")) {
			return true;
		}
		String granted = grant.permissions().get(permission);
		return granted != null && (level.equals("read") || granted.equals("write"));
	}

	/** Answers the refusal and returns true when the request's token may not do this. */
	private boolean refuse(HttpExchange exchange, Seen request, @Nullable Repo repo, String permission, String level)
			throws IOException {
		String token = bearer(request);
		Grant grant = token == null ? null : this.grants.get(token);
		if (grant == null || this.revoked.contains(token)) {
			send(exchange, 401, Map.of("message", "Bad credentials"));
			return true;
		}
		if (repo != null && (!installationCovers(grant.installation(), repo)
				|| (!grant.repositoryIds().isEmpty() && !grant.repositoryIds().contains(repo.id)))) {
			send(exchange, 404, Map.of("message", "Not Found"));
			return true;
		}
		if (repo == null && grant.installation() != PLATFORM_INSTALLATION) {
			send(exchange, 403, Map.of("message", "Resource not accessible by integration"));
			return true;
		}
		if (permission.equals("metadata")) {
			return false;
		}
		String granted = grant.permissions().get(permission);
		if (granted == null || (level.equals("write") && !granted.equals("write"))) {
			send(exchange, 403, Map.of("message", "Resource not accessible by integration"));
			return true;
		}
		return false;
	}

	private @Nullable Grant grant(Seen request) {
		String token = bearer(request);
		return token == null || this.revoked.contains(token) ? null : this.grants.get(token);
	}

	private static @Nullable String bearer(Seen request) {
		String authorization = request.authorization();
		return authorization == null || !authorization.startsWith("Bearer ") ? null : authorization.substring(7);
	}

	/** The App ID a JWT names as its issuer, or 0 for anything that is not an App JWT. */
	private static long issuer(Seen request) {
		String token = bearer(request);
		if (token == null || token.startsWith("ghs_")) {
			return 0;
		}
		String[] parts = token.split("\\.");
		if (parts.length != 3) {
			return 0;
		}
		String issuer = GitHubHttp.JSON.readTree(Base64.getUrlDecoder().decode(parts[1])).path("iss").asString("");
		return issuer.matches("\\d{1,18}") ? Long.parseLong(issuer) : 0;
	}

	private static long appOf(long installation) {
		return installation == PLATFORM_INSTALLATION ? PLATFORM_APP : installation == SOURCE_INSTALLATION ? SOURCE_APP : 0;
	}

	private static String slug(long installation) {
		return installation == SOURCE_INSTALLATION ? SOURCE_SLUG : PLATFORM_SLUG;
	}

	private static long installationOf(long app, Repo repo) {
		for (long installation : List.of(PLATFORM_INSTALLATION, SOURCE_INSTALLATION)) {
			if (appOf(installation) == app && installationCovers(installation, repo)) {
				return installation;
			}
		}
		return 0;
	}

	private static boolean installationCovers(long installation, Repo repo) {
		return installation == PLATFORM_INSTALLATION ? repo.fullName.startsWith(ORGANIZATION + "/")
				: installation == SOURCE_INSTALLATION && repo.fullName.equalsIgnoreCase(SOURCE);
	}

	/** The installation's permissions: what it accepted of the App's registration, and so what its tokens may hold. */
	private Map<String, String> ceiling(long installation) {
		Map<String, String> registered = registered(installation);
		boolean accepted = installation == SOURCE_INSTALLATION ? this.sourceAccepted : this.platformAccepted;
		if (accepted) {
			return registered;
		}
		Map<String, String> without = new LinkedHashMap<>(registered);
		without.remove("actions");
		return without;
	}

	/** The App's registration, as {@code GET /app} shows it. */
	private Map<String, String> registered(long installation) {
		if (installation == SOURCE_INSTALLATION) {
			return SpikeS6Command.SOURCE_APP_PERMISSIONS;
		}
		if (this.platformActions) {
			return AppExpectations.permissions(App.PLATFORM);
		}
		Map<String, String> withoutActions = new LinkedHashMap<>(AppExpectations.PLATFORM_PERMISSIONS);
		withoutActions.remove("actions");
		return withoutActions;
	}

	private Map<String, Object> registration(long app) {
		if (app == SOURCE_APP) {
			return map("slug", SOURCE_SLUG, "permissions", registered(SOURCE_INSTALLATION), "events", List.of());
		}
		return map("slug", PLATFORM_SLUG, "permissions", registered(PLATFORM_INSTALLATION), "events",
				List.copyOf(new TreeSet<>(AppExpectations.PLATFORM_EVENTS)));
	}

	private Map<String, Object> describe(Repo repo) {
		return map("id", repo.id, "name", repo.fullName.split("/")[1], "full_name", repo.fullName, "private",
				repo.privateRepository, "visibility", repo.privateRepository ? "private" : "public", "default_branch",
				repo.defaultBranch, "has_wiki", false, "has_issues", false, "has_projects", false, "has_discussions", false,
				// The organization forbids forking private repositories, so GitHub reports none allowed.
				"allow_forking", !repo.privateRepository);
	}

	/** {@code git hash-object}: what GitHub reports as a file's {@code sha}. */
	static String blobSha(String content) {
		try {
			byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
			MessageDigest sha1 = MessageDigest.getInstance("SHA-1");
			sha1.update(("blob " + bytes.length + "\0").getBytes(StandardCharsets.US_ASCII));
			return HexFormat.of().formatHex(sha1.digest(bytes));
		}
		catch (GeneralSecurityException ex) {
			throw new IllegalStateException(ex);
		}
	}

	private static @Nullable String parameter(@Nullable String query, String name) {
		if (query == null) {
			return null;
		}
		for (String pair : query.split("&")) {
			if (pair.startsWith(name + "=")) {
				return pair.substring(name.length() + 1);
			}
		}
		return null;
	}

	private static @Nullable Matcher match(String regex, String path) {
		Matcher matcher = Pattern.compile(regex).matcher(path);
		return matcher.matches() ? matcher : null;
	}

	/** A map that may hold nulls, in order. */
	private static Map<String, Object> map(Object... pairs) {
		Map<String, Object> map = new LinkedHashMap<>();
		for (int i = 0; i < pairs.length; i += 2) {
			map.put((String) pairs[i], pairs[i + 1]);
		}
		return map;
	}

	private static void send(HttpExchange exchange, int status, @Nullable Object body) throws IOException {
		byte[] bytes = body == null ? new byte[0] : GitHubHttp.JSON.writeValueAsBytes(body);
		exchange.getResponseHeaders().add("Content-Type", "application/json");
		exchange.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
		if (bytes.length > 0) {
			try (OutputStream out = exchange.getResponseBody()) {
				out.write(bytes);
			}
		}
		exchange.close();
	}

	private static void sendBytes(HttpExchange exchange, byte[] bytes) throws IOException {
		exchange.getResponseHeaders().add("Content-Type", "application/zip");
		exchange.sendResponseHeaders(200, bytes.length);
		try (OutputStream out = exchange.getResponseBody()) {
			out.write(bytes);
		}
		exchange.close();
	}

}
