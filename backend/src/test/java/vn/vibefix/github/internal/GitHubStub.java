package vn.vibefix.github.internal;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.diff.DiffEntry;
import org.eclipse.jgit.diff.DiffFormatter;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.treewalk.TreeWalk;
import org.eclipse.jgit.util.io.DisabledOutputStream;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import tools.jackson.databind.JsonNode;

/**
 * A stand-in for GitHub's REST API and git hosting, on loopback and {@code file://}: installation
 * tokens, organization reads, repository creation with its settings ({@code private}, the features, and
 * {@code allow_forking}, answered on read), each repository's Actions permissions under the
 * organization's policy, the runner, and invitations. It records every request and every token it handed
 * out. As GitHub does (Spike S4, Spike S6), it leaves a pending invitation at the permission it was sent
 * with, and on a private organization repository it refuses a pull-request call to a token without
 * {@code contents} read (422) and hides a branch's ref from one (404). It also answers an App's webhook
 * reads: its deliveries, newest first and paged by {@code cursor} in the {@code Link} header, their
 * redelivery, and the webhook's configuration. Its compare lists 300 files at most, as GitHub's does, and
 * its {@code POST /graphql} answers {@code object(expression:)} reads of commits and paths. For the Actions
 * exchange (Story 23.4) it serves a file's contents with its blob SHA, workflow dispatches and their runs, an
 * OIDC key set, and a revocation that can fail. A test may
 * answer what the platform no longer calls, such as Spike S4's forks and rulesets, through {@link #extra}.
 */
public final class GitHubStub implements AutoCloseable {

	public static final String ORGANIZATION = "vibefix-dev";

	public static final long INSTALLATION = 7;

	/** One request as GitHub would have received it. */
	public record Seen(String method, String path, String authorization, JsonNode body) {
	}

	public final List<Seen> seen = Collections.synchronizedList(new ArrayList<>());

	/** Runs before a request is answered, for a test that changes the world in the middle of an operation. */
	public volatile Consumer<Seen> beforeAnswer = request -> {
	};

	/** A route a test adds: it answers the request and returns {@code true}, or leaves it to the stub. */
	@FunctionalInterface
	public interface Route {

		boolean answer(HttpExchange exchange, Seen request, @org.jspecify.annotations.Nullable String query) throws Exception;

	}

	/** Answers before the stub's own routes, for GitHub behaviour only a spike's test needs (Spike S4's forks and rulesets). */
	public volatile Route extra = (exchange, request, query) -> false;

	/** The public metadata of repositories outside the organization, as a Customer's token reads them. */
	private final Map<Long, Published> published = Collections.synchronizedMap(new LinkedHashMap<>());

	final List<String> tokens = Collections.synchronizedList(new ArrayList<>());

	/** Each token's request: its {@code repository_ids} and {@code permissions}. */
	private final Map<String, JsonNode> grants = Collections.synchronizedMap(new LinkedHashMap<>());

	final AtomicInteger revoked = new AtomicInteger();

	/** {@code GET /app}'s body; tests replace it to show another registration. */
	volatile String app = "{\"slug\":\"vibefix-dev-platform\",\"permissions\":{\"contents\":\"write\",\"metadata\":\"read\"},"
			+ "\"events\":[\"push\",\"pull_request\"]}";

	/** {@code GET /app/hook/config}'s body, or none for an App whose webhook is not active (404). */
	volatile String hookConfig = "{\"url\":\"https://vibefix.test/webhooks\",\"content_type\":\"json\",\"insecure_ssl\":\"0\"}";

	/** The App's webhook deliveries, newest first, as {@code GET /app/hook/deliveries} lists them. */
	final List<Map<String, Object>> deliveries = Collections.synchronizedList(new ArrayList<>());

	/** The delivery IDs asked for again through {@code POST /app/hook/deliveries/{id}/attempts}. */
	final List<Long> redelivered = Collections.synchronizedList(new ArrayList<>());

	final Path gitRoot;

	private final HttpServer server;

	private final AtomicLong ids = new AtomicLong(1000);

	private final Map<String, Long> repositories = new LinkedHashMap<>();

	/**
	 * Each organization repository's settings by name, as {@code GET /repos/{org}/{name}} shows them:
	 * {@code private}, {@code has_wiki}, {@code has_issues}, {@code has_projects}, {@code has_discussions}, and
	 * {@code allow_forking}.
	 */
	private final Map<String, Map<String, Object>> settings = Collections.synchronizedMap(new LinkedHashMap<>());

	/** Per repository: pending invitations by invitee, with their permission, and collaborators who accepted. */
	private final Map<String, Map<String, String>> invitations = Collections.synchronizedMap(new LinkedHashMap<>());

	private final Map<String, Map<String, String>> collaborators = Collections.synchronizedMap(new LinkedHashMap<>());

	/** The organization's owners, as {@code members?role=admin} lists them. */
	public final java.util.Set<String> owners = Collections.synchronizedSet(new java.util.TreeSet<>(java.util.Set.of("vibefix-operator")));

	/** Open pull requests per repository: number to the login that opened it. */
	public final Map<String, Map<Integer, String>> pulls = Collections.synchronizedMap(new LinkedHashMap<>());

	/** The pull requests closed, as {@code name#number}. */
	public final List<String> closedPulls = Collections.synchronizedList(new ArrayList<>());

	/** The actors of each repository's recent events. */
	public final Map<String, List<String>> eventActors = Collections.synchronizedMap(new LinkedHashMap<>());

	/** {@code GET /app/installations/{id}}'s repository selection. */
	public volatile String repositorySelection = "all";

	/** The organization's Actions policy, {@code enabled_repositories}: {@code none}, {@code all}, or {@code selected}. */
	public volatile String actionsEnabled = "none";

	/** The repositories {@code selected} lets run Actions, by name. */
	public final List<String> actionsRepositories = Collections.synchronizedList(new ArrayList<>());

	/** The organization's {@code allowed_actions}: {@code local_only}, {@code all}, or {@code selected}. */
	public volatile String actionsAllowed = "local_only";

	/** {@code GET /orgs/{org}/actions/permissions/workflow}: the default token permissions, and pull request approvals. */
	public volatile String defaultWorkflowPermissions = "read";

	public volatile boolean workflowsApprovePullRequests;

	/** {@code GET /orgs/{org}}'s {@code members_can_fork_private_repositories}, which a new private repository's {@code allow_forking} follows. */
	public volatile boolean membersCanForkPrivateRepositories;

	/** Organization members: GitHub adds them at once, without an invitation (AD-11's MEMBER_DRIFT). */
	public final java.util.Set<String> members = Collections.synchronizedSet(new java.util.HashSet<>());

	/** Outside collaborators without two-factor authentication, as {@code filter=2fa_disabled} lists them. */
	public final java.util.Set<String> withoutTwoFactor = Collections.synchronizedSet(new java.util.HashSet<>());

	/**
	 * GitHub accounts by numeric ID, as {@code GET /user/{id}} answers them (AD-10 "Logins by ID"); a test
	 * renames an account by putting its new login. An ID not here has the login the tests' accounts carry,
	 * {@code user-{id}} ({@code IdentityTestSupport}), unless it is {@link #goneUsers gone}.
	 */
	public final Map<Long, String> logins = Collections.synchronizedMap(new LinkedHashMap<>());

	/** GitHub accounts deleted on GitHub: {@code GET /user/{id}} answers 404. */
	public final java.util.Set<Long> goneUsers = Collections.synchronizedSet(new java.util.HashSet<>());

	public GitHubStub(Path gitRoot) throws IOException {
		this.gitRoot = gitRoot;
		this.server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		this.server.createContext("/", this::handle);
		this.server.start();
	}

	public URI apiUrl() {
		return URI.create("http://127.0.0.1:" + this.server.getAddress().getPort());
	}

	public URI gitUrl() {
		return this.gitRoot.toUri();
	}

	/** A public source repository whose default branch holds {@code .github/workflows/ci.yml}. */
	public void source(String owner, String name) throws Exception {
		Path work = Files.createTempDirectory("spike-s4-source-");
		try (Git git = Git.init().setDirectory(work.toFile()).setInitialBranch("main").call()) {
			Files.createDirectories(work.resolve(".github/workflows"));
			Files.writeString(work.resolve(".github/workflows/ci.yml"), "on: push\n");
			Files.writeString(work.resolve("README.md"), "# Quán Mây\n");
			git.add().addFilepattern(".").call();
			git.commit().setMessage("First commit").setAuthor("Lan", "lan@example.test").setCommitter("Lan", "lan@example.test").call();
		}
		Git.cloneRepository().setURI(work.toUri().toString()).setBare(true)
			.setDirectory(this.gitRoot.resolve(owner).resolve(name + ".git").toFile()).call().close();
	}

	public Repository repository(String owner, String name) throws IOException {
		return Git.open(this.gitRoot.resolve(owner).resolve(name + ".git").toFile()).getRepository();
	}

	/** A repository outside the organization, as a Customer's token or a public read shows it. */
	private record Published(String owner, String name, long id, long ownerId, boolean publicRepository, boolean admin,
			boolean archived) {

		String json() {
			return "{\"id\":" + this.id + ",\"full_name\":\"" + this.owner + "/" + this.name + "\",\"html_url\":\"https://github.com/"
					+ this.owner + "/" + this.name + "\",\"owner\":{\"id\":" + this.ownerId + ",\"type\":\"User\"},\"private\":"
					+ !this.publicRepository + ",\"visibility\":\"" + (this.publicRepository ? "public" : "private")
					+ "\",\"archived\":" + this.archived + ",\"size\":10,\"default_branch\":\"main\",\"permissions\":{\"admin\":"
					+ this.admin + "}}";
		}

	}

	/**
	 * {@code owner/name} as {@code GET /repos/{owner}/{name}} and {@code GET /repositories/{id}} show it:
	 * public or not, owned by the user {@code ownerId}, with the token's admin permission.
	 */
	public void publish(String owner, String name, long id, long ownerId, boolean publicRepository, boolean admin) {
		this.published.put(id, new Published(owner, name, id, ownerId, publicRepository, admin, false));
	}

	/** The repository with {@code id} is archived. */
	public void archive(long id) {
		this.published.computeIfPresent(id, (key, repository) -> new Published(repository.owner(), repository.name(), key,
				repository.ownerId(), repository.publicRepository(), repository.admin(), true));
	}

	/** The repository with {@code id} is renamed or transferred, its git data copied to the new name. */
	public void rename(long id, String owner, String name) throws IOException {
		Published before = this.published.get(id);
		Path from = this.gitRoot.resolve(before.owner()).resolve(before.name() + ".git");
		Path to = this.gitRoot.resolve(owner).resolve(name + ".git");
		// A copy, not a move: JGit keeps pack files open on Windows.
		try (java.util.stream.Stream<Path> paths = Files.walk(from)) {
			for (Path path : (Iterable<Path>) paths::iterator) {
				Path target = to.resolve(from.relativize(path).toString());
				if (Files.isDirectory(path)) {
					Files.createDirectories(target);
				}
				else {
					Files.copy(path, target);
				}
			}
		}
		this.published.put(id, new Published(owner, name, id, before.ownerId(), before.publicRepository(), before.admin(),
				before.archived()));
	}

	private @org.jspecify.annotations.Nullable Published published(String owner, String name) {
		synchronized (this.published) {
			return this.published.values()
				.stream()
				.filter(repository -> repository.owner().equals(owner) && repository.name().equals(name))
				.findFirst()
				.orElse(null);
		}
	}

	/** The ID GitHub gave {@code name} in the organization. */
	public Long organizationRepositoryId(String name) {
		return this.repositories.get(name);
	}

	/** The organization repository with that ID, by name, or none. */
	private @org.jspecify.annotations.Nullable String organizationRepositoryName(long id) {
		synchronized (this.repositories) {
			return this.repositories.entrySet()
				.stream()
				.filter(entry -> entry.getValue() == id)
				.map(Map.Entry::getKey)
				.findFirst()
				.orElse(null);
		}
	}

	/** One setting of an organization repository changes, as someone on GitHub would change it ({@code has_issues}, {@code private}…). */
	public void setting(String name, String key, Object value) {
		this.settings.computeIfAbsent(name, repository -> Collections.synchronizedMap(new LinkedHashMap<>())).put(key, value);
	}

	/** One setting of an organization repository as GitHub shows it now, or none. */
	public @org.jspecify.annotations.Nullable Object setting(String name, String key) {
		return this.settings.getOrDefault(name, Map.of()).get(key);
	}

	/** Whether the organization repository is private. */
	public boolean isPrivate(String name) {
		return Boolean.TRUE.equals(setting(name, "private"));
	}

	/** The settings AD-11 gives a platform repository: private, with the features and forking off. */
	private void privateSettings(String name) {
		Map<String, Object> shown = Collections.synchronizedMap(new LinkedHashMap<>());
		shown.put("private", true);
		for (String feature : List.of("has_wiki", "has_issues", "has_projects", "has_discussions")) {
			shown.put(feature, false);
		}
		shown.put("allow_forking", false);
		this.settings.put(name, shown);
	}

	/** The published runner workflow, {@code deploy/runner/s6-copy.yml}, with LF line ends as GitHub stores it. */
	public static final Path RUNNER_WORKFLOW = Path.of("..", "deploy", "runner", "s6-copy.yml");

	/** The published Customer workflow, {@code deploy/customer/vibefix.yml}. */
	public static final Path CUSTOMER_WORKFLOW = Path.of("..", "deploy", "customer", "vibefix.yml");

	/** A published workflow's text, its CRLF line ends read as LF. */
	public static String published(Path file) throws IOException {
		return Files.readString(file, StandardCharsets.UTF_8).replace("\r\n", "\n");
	}

	/**
	 * The runner (AD-46): a private repository of the organization whose default branch holds only
	 * {@code .github/workflows/s6-copy.yml}, the published file (an accepted blob, Story 23.4), with no
	 * collaborator, and the only one the organization's Actions policy ("Selected repositories") lets run.
	 */
	public void runner(String name) throws Exception {
		Path work = Files.createTempDirectory("stub-runner-");
		try (Git git = Git.init().setDirectory(work.toFile()).setInitialBranch("main").call()) {
			Files.createDirectories(work.resolve(".github/workflows"));
			Files.writeString(work.resolve(".github/workflows/s6-copy.yml"), published(RUNNER_WORKFLOW));
			Files.writeString(work.resolve("README.md"), "# vibefix-runner\n");
			git.add().addFilepattern(".").call();
			git.commit().setMessage("The runner").setAuthor("Vibefix", "ops@example.test").setCommitter("Vibefix", "ops@example.test")
				.call();
		}
		Path bare = this.gitRoot.resolve(ORGANIZATION).resolve(name + ".git");
		if (!Files.isDirectory(bare)) {
			Git.cloneRepository().setURI(work.toUri().toString()).setBare(true).setDirectory(bare.toFile()).call().close();
			this.repositories.put(name, this.ids.incrementAndGet());
		}
		privateSettings(name);
		collaboratorsOf(name).clear();
		invitationsOf(name).clear();
		this.actionsEnabled = "selected";
		this.actionsRepositories.clear();
		this.actionsRepositories.add(name);
	}

	public boolean hasFile(String owner, String name, String branch, String path) throws IOException {
		try (Repository repository = repository(owner, name); RevWalk walk = new RevWalk(repository)) {
			var head = repository.resolve("refs/heads/" + branch);
			if (head == null) {
				return false;
			}
			RevCommit commit = walk.parseCommit(head);
			try (TreeWalk tree = TreeWalk.forPath(repository, path, commit.getTree())) {
				return tree != null;
			}
		}
	}

	@Override
	public void close() {
		this.server.stop(0);
	}

	/** A new commit on {@code branch} of {@code owner/name}, as someone pushing it; returns its SHA. */
	public String push(String owner, String name, String branch, String file, String content) throws Exception {
		return push(owner, name, branch, file, content, "Push " + file, "vy@example.test");
	}

	/**
	 * A new commit with its message and its author's email; {@link #userIds} decides whether GitHub
	 * resolves the author to an account. {@code file} may lie in folders.
	 */
	public String push(String owner, String name, String branch, String file, String content, String message,
			String authorEmail) throws Exception {
		return push(owner, name, branch, Map.of(file, content), List.of(), message, authorEmail);
	}

	/**
	 * One new commit that writes every file of {@code files} (path to content; paths may lie in folders)
	 * and removes every path of {@code removed}, a file or a whole folder, such as a push that changes
	 * hundreds of files at once. Returns its SHA.
	 */
	public String push(String owner, String name, String branch, Map<String, String> files, List<String> removed,
			String message, String authorEmail) throws Exception {
		Path work = Files.createTempDirectory("stub-push-");
		try (Git git = Git.cloneRepository().setURI(this.gitRoot.resolve(owner).resolve(name + ".git").toUri().toString())
			.setDirectory(work.toFile()).setBranch(branch).call()) {
			for (Map.Entry<String, String> file : files.entrySet()) {
				Path target = work.resolve(file.getKey());
				Files.createDirectories(target.getParent());
				Files.writeString(target, file.getValue());
			}
			git.add().addFilepattern(".").call();
			for (String path : removed) {
				git.rm().addFilepattern(path).call();
			}
			String author = authorEmail.substring(0, authorEmail.indexOf('@'));
			RevCommit commit = git.commit().setMessage(message).setAuthor(author, authorEmail)
				.setCommitter(author, authorEmail).call();
			git.push().call();
			return commit.name();
		}
	}

	/**
	 * A platform repository as Setup leaves it: a private bare copy of {@code fromOwner/fromName}, with the
	 * features and forking off, and its ID.
	 */
	public long platformRepository(String name, String fromOwner, String fromName) throws Exception {
		Git.cloneRepository().setURI(this.gitRoot.resolve(fromOwner).resolve(fromName + ".git").toUri().toString()).setBare(true)
			.setDirectory(this.gitRoot.resolve(ORGANIZATION).resolve(name + ".git").toFile()).call().close();
		long id = this.ids.incrementAndGet();
		this.repositories.put(name, id);
		privateSettings(name);
		return id;
	}

	/** The head of {@code branch}, or none. */
	public String head(String owner, String name, String branch) throws IOException {
		try (Repository repository = repository(owner, name)) {
			var head = repository.resolve("refs/heads/" + branch);
			return head == null ? null : head.name();
		}
	}

	private void handle(HttpExchange exchange) throws IOException {
		String method = exchange.getRequestMethod();
		String path = exchange.getRequestURI().getPath();
		String query = exchange.getRequestURI().getQuery();
		String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
		JsonNode json = body.isBlank() ? GitHubHttp.JSON.createObjectNode() : GitHubHttp.JSON.readTree(body);
		Seen request = new Seen(method, path, exchange.getRequestHeaders().getFirst("Authorization"), json);
		this.seen.add(request);
		this.beforeAnswer.accept(request);
		if (this.unavailable.stream().anyMatch(prefix -> (method + " " + path).startsWith(prefix))) {
			send(exchange, 502, "{\"message\":\"Bad Gateway\"}");
			return;
		}
		try {
			if (!this.extra.answer(exchange, request, query)) {
				route(exchange, method, path, query, json);
			}
		}
		catch (Exception ex) {
			send(exchange, 500, "{\"message\":\"" + ex.getClass().getSimpleName() + "\"}");
		}
	}

	/** Every pull-request call on an organization repository: open, look up, list, read, merge, close, and comment. */
	private static final Pattern PULL_CALL = Pattern
		.compile("/repos/" + ORGANIZATION + "/([^/]+)/(?:pulls(?:/\\d+(?:/merge)?)?|issues/\\d+/comments)");

	private void route(HttpExchange exchange, String method, String path, String query, JsonNode body) throws Exception {
		Matcher matcher;
		if ((matcher = PULL_CALL.matcher(path)).matches() && isPrivate(matcher.group(1)) && !readsContents(exchange)) {
			// Spike S6 Part C: on a private repository GitHub needs contents read besides pull_requests.
			send(exchange, 422, "{\"message\":\"Validation Failed\",\"errors\":[{\"resource\":\"PullRequest\",\"field\":\"head\","
					+ "\"code\":\"invalid\"}]}");
		}
		else if (method.equals("GET") && (matcher = Pattern.compile("/user/(\\d+)").matcher(path)).matches()) {
			long id = Long.parseLong(matcher.group(1));
			if (this.goneUsers.contains(id)) {
				send(exchange, 404, "{\"message\":\"Not Found\"}");
				return;
			}
			send(exchange, 200, GitHubHttp.JSON.writeValueAsString(Map.of("id", id, "login", loginOf(id))));
		}
		else if (method.equals("GET") && path.equals("/.well-known/jwks")) {
			// GitHub Actions' OIDC issuer, when a test names this stub as the issuer (Story 23.4).
			String keys = this.jwks;
			send(exchange, keys == null ? 404 : 200, keys == null ? "{\"message\":\"Not Found\"}" : keys);
		}
		else if (method.equals("POST")
				&& (matcher = Pattern.compile("/repos/([^/]+)/([^/]+)/actions/workflows/([^/]+)/dispatches").matcher(path))
					.matches()) {
			dispatch(exchange, matcher.group(1), matcher.group(2), matcher.group(3), body);
		}
		else if (method.equals("GET")
				&& (matcher = Pattern.compile("/repos/([^/]+)/([^/]+)/actions/runs/(\\d+)").matcher(path)).matches()) {
			Map<String, Object> run = this.runs.get(Long.parseLong(matcher.group(3)));
			boolean here = run != null && (matcher.group(1) + "/" + matcher.group(2)).equals(run.get("repository_full_name"));
			send(exchange, here ? 200 : 404, here ? GitHubHttp.JSON.writeValueAsString(run) : "{\"message\":\"Not Found\"}");
		}
		else if (method.equals("GET")
				&& (matcher = Pattern.compile("/repos/([^/]+)/([^/]+)/actions/workflows/([^/]+)/runs").matcher(path))
					.matches()) {
			String repository = matcher.group(1) + "/" + matcher.group(2);
			String workflowPath = ".github/workflows/" + matcher.group(3);
			List<Map<String, Object>> listed = new ArrayList<>();
			synchronized (this.runs) {
				this.runs.values()
					.stream()
					.filter(run -> !this.runsHidden && repository.equals(run.get("repository_full_name"))
							&& workflowPath.equals(run.get("path")))
					.forEach(run -> listed.add(0, run));
			}
			send(exchange, 200, GitHubHttp.JSON.writeValueAsString(Map.of("total_count", listed.size(), "workflow_runs", listed)));
		}
		else if (method.equals("POST") && path.equals("/app/installations/" + INSTALLATION + "/access_tokens")
				&& this.mintRefused.test(body)) {
			send(exchange, 500, "{\"message\":\"Server Error\"}");
		}
		else if (method.equals("POST") && path.equals("/app/installations/" + INSTALLATION + "/access_tokens")) {
			String token = this.tokenFormat.apply(this.tokens.size());
			this.tokens.add(token);
			this.grants.put(token, body);
			send(exchange, 201, "{\"token\":\"" + token + "\",\"expires_at\":\"2026-10-19T04:00:00Z\"}");
		}
		else if (method.equals("POST") && (matcher = Pattern.compile("/app/installations/(\\d+)/access_tokens").matcher(path)).matches()
				&& this.mergeInstallations.containsKey(Long.parseLong(matcher.group(1)))) {
			String token = "ghs_stubtoken_" + this.tokens.size() + "_m3rg3";
			this.tokens.add(token);
			this.grants.put(token, body);
			this.tokenInstallations.put(token, Long.parseLong(matcher.group(1)));
			send(exchange, 201, "{\"token\":\"" + token + "\",\"expires_at\":\"2026-10-19T04:00:00Z\"}");
		}
		else if (method.equals("GET") && (matcher = Pattern.compile("/app/installations/(\\d+)").matcher(path)).matches()
				&& Long.parseLong(matcher.group(1)) != INSTALLATION) {
			MergeInstallation installation = this.mergeInstallations.get(Long.parseLong(matcher.group(1)));
			if (installation == null) {
				send(exchange, 404, "{\"message\":\"Not Found\"}");
				return;
			}
			send(exchange, 200, GitHubHttp.JSON.writeValueAsString(Map.of("id", Long.parseLong(matcher.group(1)), "account",
					Map.of("id", installation.accountId, "login", installation.accountLogin), "repository_selection",
					installation.all ? "all" : "selected", "suspended_at", installation.suspended ? "2026-10-22T00:00:00Z" : "")));
		}
		else if (method.equals("DELETE") && (matcher = Pattern.compile("/app/installations/(\\d+)").matcher(path)).matches()) {
			long id = Long.parseLong(matcher.group(1));
			if (this.mergeInstallations.remove(id) == null) {
				send(exchange, 404, "{\"message\":\"Not Found\"}");
				return;
			}
			this.removedInstallations.add(id);
			send(exchange, 204, "");
		}
		else if (method.equals("GET") && path.equals("/installation/repositories")) {
			String authorization = exchange.getRequestHeaders().getFirst("Authorization");
			Long id = authorization == null ? null : this.tokenInstallations.get(authorization.replaceFirst("^Bearer ", ""));
			MergeInstallation installation = id == null ? null : this.mergeInstallations.get(id);
			if (installation == null) {
				send(exchange, 401, "{\"message\":\"Bad credentials\"}");
				return;
			}
			List<Map<String, Object>> repositories;
			synchronized (installation.repositories) {
				repositories = List.copyOf(installation.repositories);
			}
			send(exchange, 200, GitHubHttp.JSON.writeValueAsString(Map.of("total_count", repositories.size(), "repositories",
					repositories)));
		}
		else if (method.equals("GET") && path.equals("/app/installation-requests")) {
			List<Map<String, Object>> requests;
			synchronized (this.installationRequests) {
				requests = List.copyOf(this.installationRequests);
			}
			send(exchange, 200, GitHubHttp.JSON.writeValueAsString(requests));
		}
		else if (method.equals("GET")
				&& (matcher = Pattern.compile("/repos/([^/]+)/([^/]+)/contents/\\.github/workflows").matcher(path)).matches()) {
			// A Source Repository's workflows (SG-19), or a platform repository's, such as the runner's one workflow.
			String branch = java.net.URLDecoder.decode(parameter(query, "ref", "main"), StandardCharsets.UTF_8);
			List<Map<String, Object>> files = new ArrayList<>();
			try (Repository git = repository(matcher.group(1), matcher.group(2)); RevWalk walk = new RevWalk(git)) {
				var head = git.resolve("refs/heads/" + branch);
				if (head != null) {
					try (TreeWalk tree = new TreeWalk(git)) {
						tree.addTree(walk.parseCommit(head).getTree());
						tree.setRecursive(true);
						while (tree.next()) {
							if (tree.getPathString().startsWith(".github/workflows/")) {
								files.add(Map.of("name", tree.getNameString(), "path", tree.getPathString(), "type", "file"));
							}
						}
					}
				}
			}
			if (files.isEmpty()) {
				send(exchange, 404, GitHubHttp.JSON.writeValueAsString(Map.of("message", "Not Found")));
				return;
			}
			send(exchange, 200, GitHubHttp.JSON.writeValueAsString(files));
		}
		else if (method.equals("GET") && (matcher = Pattern.compile("/repos/([^/]+)/([^/]+)/contents/(.+)").matcher(path)).matches()) {
			// One file at a branch or a commit, as the contents API shows it: its blob SHA and base64 content
			// (Story 23.4's accepted workflow blobs). A private organization repository hides it from a token
			// without contents read.
			boolean hidden = matcher.group(1).equals(ORGANIZATION) && isPrivate(matcher.group(2)) && !readsContents(exchange);
			String ref = java.net.URLDecoder.decode(parameter(query, "ref", "main"), StandardCharsets.UTF_8);
			Map<String, Object> file = hidden ? null : file(matcher.group(1), matcher.group(2), ref, matcher.group(3));
			send(exchange, file == null ? 404 : 200,
					file == null ? "{\"message\":\"Not Found\"}" : GitHubHttp.JSON.writeValueAsString(file));
		}
		else if ((method.equals("GET") || method.equals("POST"))
				&& (matcher = Pattern.compile("/repos/([^/]+)/([^/]+)/pulls").matcher(path)).matches()
				&& !matcher.group(1).equals(ORGANIZATION)) {
			String repository = matcher.group(1) + "/" + matcher.group(2);
			Map<Integer, Map<String, String>> pulls = this.sourcePulls.computeIfAbsent(repository,
					name -> Collections.synchronizedMap(new LinkedHashMap<>()));
			if (method.equals("GET")) {
				String head = parameter(query, "head", "");
				String branch = java.net.URLDecoder.decode(head, StandardCharsets.UTF_8);
				branch = branch.substring(branch.indexOf(':') + 1);
				List<Map<String, Object>> open = new ArrayList<>();
				synchronized (pulls) {
					for (var entry : pulls.entrySet()) {
						if (entry.getValue().get("head").equals(branch) && !"true".equals(entry.getValue().get("merged"))) {
							open.add(Map.of("number", entry.getKey(), "html_url", "https://github.com/" + repository + "/pull/"
									+ entry.getKey()));
						}
					}
				}
				send(exchange, 200, GitHubHttp.JSON.writeValueAsString(open));
				return;
			}
			int number = pulls.size() + 1;
			pulls.put(number, new java.util.concurrent.ConcurrentHashMap<>(Map.of("head", body.path("head").asString(), "base",
					body.path("base").asString(), "merged", "false")));
			send(exchange, 201, GitHubHttp.JSON.writeValueAsString(Map.of("number", number, "html_url",
					"https://github.com/" + repository + "/pull/" + number)));
		}
		else if (method.equals("PUT")
				&& (matcher = Pattern.compile("/repos/([^/]+)/([^/]+)/pulls/(\\d+)/merge").matcher(path)).matches()
				&& !matcher.group(1).equals(ORGANIZATION)) {
			String repository = matcher.group(1) + "/" + matcher.group(2);
			Map<String, String> pull = this.sourcePulls.getOrDefault(repository, Map.of()).get(Integer.parseInt(matcher.group(3)));
			Integer refusal = this.sourceMergeRefusal;
			if (pull == null) {
				send(exchange, 404, GitHubHttp.JSON.writeValueAsString(Map.of("message", "Not Found")));
				return;
			}
			if (refusal != null) {
				send(exchange, refusal, GitHubHttp.JSON.writeValueAsString(Map.of("message", "Repository rule violations found")));
				return;
			}
			try (Repository git = repository(matcher.group(1), matcher.group(2)); RevWalk walk = new RevWalk(git);
					org.eclipse.jgit.lib.ObjectInserter inserter = git.newObjectInserter()) {
				RevCommit base = walk.parseCommit(git.resolve("refs/heads/" + pull.get("base")));
				RevCommit head = walk.parseCommit(git.resolve("refs/heads/" + pull.get("head")));
				if (!head.name().equals(body.path("sha").asString())) {
					send(exchange, 409, GitHubHttp.JSON.writeValueAsString(Map.of("message", "Head branch was modified")));
					return;
				}
				org.eclipse.jgit.merge.ThreeWayMerger merger = org.eclipse.jgit.merge.MergeStrategy.RECURSIVE.newMerger(git, true);
				if (!merger.merge(base, head)) {
					send(exchange, 405, GitHubHttp.JSON.writeValueAsString(Map.of("message", "Pull Request is not mergeable")));
					return;
				}
				org.eclipse.jgit.lib.CommitBuilder commit = new org.eclipse.jgit.lib.CommitBuilder();
				commit.setTreeId(merger.getResultTreeId());
				commit.setParentIds(base, head);
				org.eclipse.jgit.lib.PersonIdent bot = new org.eclipse.jgit.lib.PersonIdent("vibefix-merge[bot]", "bot@example.test");
				commit.setAuthor(bot);
				commit.setCommitter(bot);
				commit.setMessage(body.path("commit_title").asString("Merge"));
				var merged = inserter.insert(commit);
				inserter.flush();
				org.eclipse.jgit.lib.RefUpdate update = git.updateRef("refs/heads/" + pull.get("base"));
				update.setNewObjectId(merged);
				update.setExpectedOldObjectId(base);
				update.update();
				pull.put("merged", "true");
				send(exchange, 200, GitHubHttp.JSON.writeValueAsString(Map.of("sha", merged.name(), "merged", true)));
			}
		}
		else if (method.equals("GET")
				&& (matcher = Pattern.compile("/repos/([^/]+)/([^/]+)/pulls/(\\d+)").matcher(path)).matches()
				&& !matcher.group(1).equals(ORGANIZATION)) {
			Map<String, String> pull = this.sourcePulls.getOrDefault(matcher.group(1) + "/" + matcher.group(2), Map.of())
				.get(Integer.parseInt(matcher.group(3)));
			send(exchange, pull == null ? 404 : 200, GitHubHttp.JSON.writeValueAsString(pull == null ? Map.of("message", "Not Found")
					: Map.of("number", Integer.parseInt(matcher.group(3)), "merged", "true".equals(pull.get("merged")))));
		}
		else if (method.equals("DELETE")
				&& (matcher = Pattern.compile("/repos/([^/]+)/([^/]+)/git/refs/heads/(.+)").matcher(path)).matches()
				&& !matcher.group(1).equals(ORGANIZATION)) {
			try (Repository git = repository(matcher.group(1), matcher.group(2))) {
				org.eclipse.jgit.lib.RefUpdate delete = git.updateRef("refs/heads/" + matcher.group(3));
				delete.setForceUpdate(true);
				var result = delete.delete();
				send(exchange, result == org.eclipse.jgit.lib.RefUpdate.Result.NO_CHANGE ? 422 : 204, "");
			}
		}
		else if (method.equals("DELETE") && path.equals("/installation/token")) {
			int status = this.revocationStatus;
			if (status == 204) {
				this.revoked.incrementAndGet();
				String authorization = exchange.getRequestHeaders().getFirst("Authorization");
				this.revokedTokens.add(authorization == null ? "" : authorization.replaceFirst("^Bearer ", ""));
			}
			send(exchange, status, status == 204 ? "" : "{\"message\":\"Server Error\"}");
		}
		else if (method.equals("GET") && path.equals("/app")) {
			send(exchange, 200, this.app);
		}
		else if (method.equals("GET") && path.equals("/app/hook/config")) {
			String config = this.hookConfig;
			send(exchange, config == null ? 404 : 200, config == null ? "{\"message\":\"Not Found\"}" : config);
		}
		else if (method.equals("GET") && path.equals("/app/hook/deliveries")) {
			int size = Integer.parseInt(parameter(query, "per_page", "30"));
			int from = Integer.parseInt(parameter(query, "cursor", "0"));
			List<Map<String, Object>> page;
			synchronized (this.deliveries) {
				page = List.copyOf(this.deliveries.subList(Math.min(from, this.deliveries.size()),
						Math.min(from + size, this.deliveries.size())));
			}
			String next = from + size < this.deliveries.size()
					? "<" + apiUrl() + "/app/hook/deliveries?per_page=" + size + "&cursor=" + (from + size) + ">; rel=\"next\""
					: null;
			send(exchange, 200, GitHubHttp.JSON.writeValueAsString(page), next);
		}
		else if (method.equals("POST") && (matcher = Pattern.compile("/app/hook/deliveries/(\\d+)/attempts").matcher(path)).matches()) {
			this.redelivered.add(Long.parseLong(matcher.group(1)));
			send(exchange, 202, "{}");
		}
		else if (method.equals("GET") && path.equals("/app/installations/" + INSTALLATION)) {
			send(exchange, 200, "{\"id\":" + INSTALLATION + ",\"repository_selection\":\"" + this.repositorySelection + "\"}");
		}
		else if (method.equals("GET") && path.equals("/orgs/" + ORGANIZATION)) {
			send(exchange, 200, "{\"login\":\"" + ORGANIZATION
					+ "\",\"two_factor_requirement_enabled\":true,\"default_repository_permission\":\"none\","
					+ "\"members_can_fork_private_repositories\":" + this.membersCanForkPrivateRepositories + "}");
		}
		else if (method.equals("GET") && path.equals("/orgs/" + ORGANIZATION + "/actions/permissions")) {
			send(exchange, 200, "{\"enabled_repositories\":\"" + this.actionsEnabled + "\",\"allowed_actions\":\""
					+ this.actionsAllowed + "\"}");
		}
		else if (method.equals("GET") && path.equals("/orgs/" + ORGANIZATION + "/actions/permissions/workflow")) {
			send(exchange, 200, "{\"default_workflow_permissions\":\"" + this.defaultWorkflowPermissions
					+ "\",\"can_approve_pull_request_reviews\":" + this.workflowsApprovePullRequests + "}");
		}
		else if (method.equals("GET") && path.equals("/orgs/" + ORGANIZATION + "/actions/permissions/repositories")) {
			List<Map<String, Object>> listed = new ArrayList<>();
			synchronized (this.actionsRepositories) {
				for (String name : this.actionsRepositories) {
					listed.add(Map.of("id", this.repositories.getOrDefault(name, 0L), "name", name, "full_name",
							ORGANIZATION + "/" + name));
				}
			}
			send(exchange, 200, GitHubHttp.JSON.writeValueAsString(Map.of("total_count", listed.size(), "repositories", listed)));
		}
		else if (method.equals("POST") && path.equals("/orgs/" + ORGANIZATION + "/repos")) {
			String name = body.path("name").asString();
			Git.init().setBare(true).setInitialBranch("main")
				.setDirectory(this.gitRoot.resolve(ORGANIZATION).resolve(name + ".git").toFile()).call().close();
			long id = this.ids.incrementAndGet();
			this.repositories.put(name, id);
			// As GitHub records them: public unless asked, the features on and forking allowed unless turned off.
			Map<String, Object> shown = Collections.synchronizedMap(new LinkedHashMap<>());
			shown.put("private", body.path("private").asBoolean(false));
			for (String feature : List.of("has_wiki", "has_issues", "has_projects", "has_discussions")) {
				shown.put(feature, body.path(feature).asBoolean(true));
			}
			// GitHub's create takes no allow_forking: a private repository follows the organization's setting.
			shown.put("allow_forking", !Boolean.TRUE.equals(shown.get("private")) || this.membersCanForkPrivateRepositories);
			this.settings.put(name, shown);
			send(exchange, 201, organizationRepository(name, id));
		}
		else if (method.equals("GET")
				&& (matcher = Pattern.compile("/repos/" + ORGANIZATION + "/([^/]+)/actions/permissions").matcher(path)).matches()) {
			String name = matcher.group(1);
			if (!this.repositories.containsKey(name)) {
				send(exchange, 404, "{\"message\":\"Not Found\"}");
				return;
			}
			// The organization's policy decides: "selected" enables only the repositories it lists.
			boolean enabled = "all".equals(this.actionsEnabled) || ("selected".equals(this.actionsEnabled)
					&& this.actionsRepositories.stream().anyMatch(name::equalsIgnoreCase));
			send(exchange, 200, enabled ? "{\"enabled\":true,\"allowed_actions\":\"" + this.actionsAllowed + "\"}"
					: "{\"enabled\":false}");
		}
		else if (method.equals("GET")
				&& (matcher = Pattern.compile("/repos/" + ORGANIZATION + "/([^/]+)/git/matching-refs/(heads|tags)").matcher(path))
					.matches()) {
			List<Map<String, Object>> refs = new ArrayList<>();
			try (Repository repository = repository(ORGANIZATION, matcher.group(1))) {
				for (org.eclipse.jgit.lib.Ref ref : repository.getRefDatabase().getRefsByPrefix("refs/" + matcher.group(2) + "/")) {
					refs.add(Map.of("ref", ref.getName(), "object", Map.of("sha", ref.getObjectId().name())));
				}
			}
			send(exchange, 200, GitHubHttp.JSON.writeValueAsString(refs));
		}
		else if ((matcher = Pattern.compile("/repos/" + ORGANIZATION + "/([^/]+)/collaborators/([^/]+)/permission").matcher(path))
			.matches()) {
			String permission = collaboratorsOf(matcher.group(1)).getOrDefault(matcher.group(2), "none");
			send(exchange, 200, "{\"permission\":\"" + permission + "\",\"role_name\":\"" + permission + "\"}");
		}
		else if (method.equals("GET") && path.equals("/orgs/" + ORGANIZATION + "/outside_collaborators")) {
			List<Map<String, Object>> list = new ArrayList<>();
			if (query != null && query.contains("filter=2fa_disabled")) {
				this.withoutTwoFactor.forEach(login -> list.add(user(login)));
			}
			send(exchange, 200, GitHubHttp.JSON.writeValueAsString(list));
		}
		else if (method.equals("GET") && path.equals("/orgs/" + ORGANIZATION + "/members")) {
			boolean admins = query != null && query.contains("role=admin");
			List<Map<String, String>> list = new ArrayList<>();
			(admins ? this.owners : this.members).forEach(login -> list.add(Map.of("login", login)));
			send(exchange, 200, GitHubHttp.JSON.writeValueAsString(list));
		}
		else if (method.equals("GET") && (matcher = Pattern.compile("/orgs/" + ORGANIZATION + "/members/([^/]+)").matcher(path))
			.matches()) {
			send(exchange, this.members.contains(matcher.group(1)) ? 204 : 404, "");
		}
		else if (method.equals("GET") && (matcher = Pattern.compile("/repos/" + ORGANIZATION + "/([^/]+)/collaborators").matcher(path))
			.matches()) {
			send(exchange, 200, GitHubHttp.JSON.writeValueAsString(collaboratorsOf(matcher.group(1)).entrySet()
				.stream()
				.map(collaborator -> {
					Map<String, Object> user = user(collaborator.getKey());
					user.put("role_name", collaborator.getValue());
					return user;
				})
				.toList()));
		}
		else if ((matcher = Pattern.compile("/repos/" + ORGANIZATION + "/([^/]+)/collaborators/([^/]+)").matcher(path)).matches()) {
			Map<String, String> collaborators = collaboratorsOf(matcher.group(1));
			Map<String, String> invitations = invitationsOf(matcher.group(1));
			String login = matcher.group(2);
			String permission = body.path("permission").asString("").equals("push") ? "write" : "read";
			if (method.equals("DELETE")) {
				collaborators.remove(login);
				send(exchange, 204, "");
			}
			else if (!method.equals("GET") && this.members.contains(login) && !collaborators.containsKey(login)) {
				// An organization member is added at once: 204 without an invitation.
				collaborators.put(login, permission);
				send(exchange, 204, "");
			}
			else if (method.equals("GET")) {
				send(exchange, collaborators.containsKey(login) ? 204 : 404, "");
			}
			else if (collaborators.containsKey(login)) {
				collaborators.put(login, permission);
				send(exchange, 204, "");
			}
			else {
				// A pending invitation keeps the permission it was sent with.
				invitations.putIfAbsent(login, permission);
				send(exchange, 201, "{\"id\":1}");
			}
		}
		else if (method.equals("DELETE")
				&& (matcher = Pattern.compile("/repos/" + ORGANIZATION + "/([^/]+)/invitations/(\\d+)").matcher(path)).matches()) {
			Map<String, String> invitations = invitationsOf(matcher.group(1));
			String login = this.invitationIds.remove(Long.parseLong(matcher.group(2)));
			if (login != null) {
				invitations.remove(login);
			}
			send(exchange, 204, "");
		}
		else if (method.equals("DELETE") && (matcher = Pattern.compile("/repos/" + ORGANIZATION + "/([^/]+)").matcher(path)).matches()) {
			this.repositories.remove(matcher.group(1));
			this.deleted.add(matcher.group(1));
			send(exchange, 204, "");
		}
		else if ((matcher = Pattern.compile("/repos/" + ORGANIZATION + "/([^/]+)/invitations").matcher(path)).matches()) {
			send(exchange, 200, GitHubHttp.JSON.writeValueAsString(invitationsOf(matcher.group(1)).entrySet()
				.stream()
				.map(invitation -> Map.of("id", invitationId(invitation.getKey()), "invitee", Map.of("login", invitation.getKey()),
						"permissions", invitation.getValue()))
				.toList()));
		}
		else if (method.equals("POST") && (matcher = Pattern.compile("/repos/" + ORGANIZATION + "/([^/]+)/pulls").matcher(path))
			.matches()) {
			int number = this.pullNumbers.incrementAndGet();
			this.pulls.computeIfAbsent(matcher.group(1), name -> Collections.synchronizedMap(new LinkedHashMap<>()))
				.put(number, "vibefix-dev-platform[bot]");
			this.pullHeads.computeIfAbsent(matcher.group(1), name -> Collections.synchronizedMap(new LinkedHashMap<>()))
				.put(number, body.path("head").asString());
			this.pullTitles.put(matcher.group(1) + "#" + number, body.path("title").asString(""));
			send(exchange, 201, "{\"number\":" + number + "}");
		}
		else if (method.equals("GET") && (matcher = Pattern.compile("/repos/" + ORGANIZATION + "/([^/]+)/pulls").matcher(path))
			.matches() && query != null && query.contains("head=")) {
			String head = java.net.URLDecoder.decode(parameter(query, "head", ""), StandardCharsets.UTF_8);
			String branch = head.substring(head.indexOf(':') + 1);
			send(exchange, 200, GitHubHttp.JSON.writeValueAsString(this.pullHeads.getOrDefault(matcher.group(1), Map.of()).entrySet()
				.stream()
				.filter(pull -> pull.getValue().equals(branch))
				.map(pull -> Map.of("number", pull.getKey(), "head", Map.of("ref", pull.getValue())))
				.toList()));
		}
		else if (method.equals("GET") && (matcher = Pattern.compile("/repos/" + ORGANIZATION + "/([^/]+)/pulls").matcher(path))
			.matches()) {
			send(exchange, 200, GitHubHttp.JSON.writeValueAsString(this.pulls.getOrDefault(matcher.group(1), Map.of()).entrySet()
				.stream()
				.map(pull -> Map.of("number", pull.getKey(), "user", user(pull.getValue())))
				.toList()));
		}
		else if (method.equals("PUT")
				&& (matcher = Pattern.compile("/repos/" + ORGANIZATION + "/([^/]+)/pulls/(\\d+)/merge").matcher(path)).matches()) {
			String repository = matcher.group(1);
			int number = Integer.parseInt(matcher.group(2));
			String head = this.pullHeads.getOrDefault(repository, Map.of()).get(number);
			if (head == null || this.mergedPulls.contains(repository + "#" + number)) {
				send(exchange, 405, "{\"message\":\"Pull Request is not mergeable\"}");
				return;
			}
			Integer refusal = this.mergeRefusals.get(repository);
			if (refusal != null) {
				send(exchange, refusal, GitHubHttp.JSON.writeValueAsString(Map.of("message",
						refusal == 405 ? "Pull Request is not mergeable" : "Validation Failed")));
				return;
			}
			try (Repository git = repository(ORGANIZATION, repository)) {
				var tip = git.resolve("refs/heads/" + head);
				if (tip == null || !tip.name().equals(body.path("sha").asString())) {
					send(exchange, 409, "{\"message\":\"Head branch was modified\"}");
					return;
				}
				// The default branch takes the handed-over commit, as a merge would bring it in.
				org.eclipse.jgit.lib.RefUpdate update = git.updateRef("refs/heads/main");
				update.setNewObjectId(tip);
				update.forceUpdate();
			}
			this.mergedPulls.add(repository + "#" + number);
			this.pulls.getOrDefault(repository, new LinkedHashMap<>()).remove(number);
			send(exchange, 200, "{\"merged\":true}");
		}
		else if (method.equals("GET")
				&& (matcher = Pattern.compile("/repos/" + ORGANIZATION + "/([^/]+)/pulls/(\\d+)").matcher(path)).matches()) {
			String pull = matcher.group(1) + "#" + matcher.group(2);
			boolean merged = this.mergedPulls.contains(pull);
			boolean closed = merged || this.closedPulls.contains(pull);
			send(exchange, 200, GitHubHttp.JSON.writeValueAsString(Map.of("number", Integer.parseInt(matcher.group(2)), "merged",
					merged, "state", closed ? "closed" : "open", "title", this.pullTitles.getOrDefault(pull, ""))));
		}
		else if (method.equals("PATCH")
				&& (matcher = Pattern.compile("/repos/" + ORGANIZATION + "/([^/]+)/pulls/(\\d+)").matcher(path)).matches()) {
			String pull = matcher.group(1) + "#" + matcher.group(2);
			if (body.path("title").isString()) {
				this.pullTitles.put(pull, body.path("title").asString());
			}
			if ("closed".equals(body.path("state").asString(""))) {
				this.pulls.getOrDefault(matcher.group(1), new LinkedHashMap<>()).remove(Integer.parseInt(matcher.group(2)));
				this.closedPulls.add(pull);
			}
			boolean closed = this.mergedPulls.contains(pull) || this.closedPulls.contains(pull);
			send(exchange, 200, GitHubHttp.JSON.writeValueAsString(Map.of("state", closed ? "closed" : "open", "title",
					this.pullTitles.getOrDefault(pull, ""))));
		}
		else if ((matcher = Pattern.compile("/repos/" + ORGANIZATION + "/([^/]+)/issues/(\\d+)/comments").matcher(path))
			.matches()) {
			// A pull request's conversation, as GitHub's issue comments API serves it.
			List<String> comments = this.pullComments.computeIfAbsent(matcher.group(1) + "#" + matcher.group(2),
					pull -> Collections.synchronizedList(new ArrayList<>()));
			if (method.equals("POST")) {
				comments.add(body.path("body").asString(""));
				send(exchange, 201, GitHubHttp.JSON.writeValueAsString(Map.of("id", this.ids.incrementAndGet(), "body",
						body.path("body").asString(""))));
			}
			else {
				send(exchange, 200, GitHubHttp.JSON.writeValueAsString(List.copyOf(comments).stream()
					.map(comment -> Map.of("body", comment))
					.toList()));
			}
		}
		else if (method.equals("GET") && (matcher = Pattern.compile("/repos/" + ORGANIZATION + "/([^/]+)/events").matcher(path))
			.matches()) {
			send(exchange, 200, GitHubHttp.JSON.writeValueAsString(this.eventActors.getOrDefault(matcher.group(1), List.of())
				.stream()
				.map(login -> Map.of("type", "PushEvent", "actor", Map.of("login", login)))
				.toList()));
		}
		else if ((matcher = Pattern.compile("/repos/" + ORGANIZATION + "/([^/]+)").matcher(path)).matches()) {
			// A private repository is hidden from a token that reads neither its metadata nor its contents.
			Long id = isPrivate(matcher.group(1)) && !readsMetadata(exchange) ? null : this.repositories.get(matcher.group(1));
			send(exchange, id == null ? 404 : 200, id == null ? "{\"message\":\"Not Found\"}"
					: organizationRepository(matcher.group(1), id));
		}
		else if (method.equals("GET") && (matcher = Pattern.compile("/repositories/(\\d+)").matcher(path)).matches()) {
			long id = Long.parseLong(matcher.group(1));
			Published repository = this.published.get(id);
			String platform = organizationRepositoryName(id);
			if (repository == null && platform != null && (!isPrivate(platform) || readsMetadata(exchange))) {
				// An organization repository by its ID, as the Actions dispatch reads its target (Story 23.4).
				send(exchange, 200, organizationRepository(platform, id));
				return;
			}
			boolean visible = repository != null && repository.publicRepository();
			send(exchange, visible ? 200 : 404, visible ? repository.json() : "{\"message\":\"Not Found\"}");
		}
		else if (method.equals("GET")
				&& (matcher = Pattern.compile("/repos/([^/]+)/([^/]+)/compare/([0-9a-f]{40})\\.\\.\\.(.+)").matcher(path)).matches()) {
			try (Repository repository = repository(matcher.group(1), matcher.group(2)); RevWalk walk = new RevWalk(repository)) {
				var base = repository.resolve(matcher.group(3));
				var head = repository.resolve("refs/heads/" + matcher.group(4));
				if (head == null && matcher.group(4).matches("[0-9a-f]{40}")) {
					head = repository.resolve(matcher.group(4));
				}
				if (base == null || head == null || !repository.getObjectDatabase().has(base)
						|| !repository.getObjectDatabase().has(head)) {
					send(exchange, 404, "{\"message\":\"Not Found\"}");
					return;
				}
				walk.markStart(walk.parseCommit(head));
				walk.markUninteresting(walk.parseCommit(base));
				List<Map<String, Object>> commits = new ArrayList<>();
				for (RevCommit commit : walk) {
					Map<String, Object> entry = new LinkedHashMap<>();
					entry.put("sha", commit.name());
					entry.put("commit", Map.of("message", commit.getFullMessage()));
					Long user = this.userIds.get(commit.getAuthorIdent().getEmailAddress());
					entry.put("author", user == null ? null : Map.of("id", user, "login", commit.getAuthorIdent().getName()));
					commits.add(0, entry);
				}
				List<Map<String, String>> files = new ArrayList<>();
				try (RevWalk trees = new RevWalk(repository); DiffFormatter diff = new DiffFormatter(DisabledOutputStream.INSTANCE)) {
					diff.setRepository(repository);
					for (DiffEntry change : diff.scan(trees.parseCommit(base).getTree(), trees.parseCommit(head).getTree())) {
						files.add(Map.of("filename",
								change.getChangeType() == DiffEntry.ChangeType.DELETE ? change.getOldPath() : change.getNewPath()));
					}
				}
				// As GitHub does, the list stops at 300 files, in path order: what sorts later is not shown.
				send(exchange, 200, GitHubHttp.JSON.writeValueAsString(Map.of("ahead_by", commits.size(), "total_commits",
						commits.size(), "commits", commits, "files", files.subList(0, Math.min(files.size(), COMPARE_FILES)))));
			}
		}
		else if (method.equals("GET")
				&& (matcher = Pattern.compile("/repos/([^/]+)/([^/]+)/git/ref/heads/(.+)").matcher(path)).matches()) {
			// A branch's ref (branchHead): the Source Repository's default branch J7 copies, or the Working
			// Repository's that a handover reads. A private repository hides it from a token without contents read.
			boolean hidden = matcher.group(1).equals(ORGANIZATION) && isPrivate(matcher.group(2)) && !readsContents(exchange);
			String head = !hidden && Files.isDirectory(this.gitRoot.resolve(matcher.group(1)).resolve(matcher.group(2) + ".git"))
					? head(matcher.group(1), matcher.group(2), matcher.group(3)) : null;
			send(exchange, head == null ? 404 : 200, head == null ? "{\"message\":\"Not Found\"}"
					: "{\"ref\":\"refs/heads/" + matcher.group(3) + "\",\"object\":{\"type\":\"commit\",\"sha\":\"" + head
							+ "\"}}");
		}
		else if (method.equals("POST") && path.equals("/graphql")) {
			graphql(exchange, body);
		}
		else if (method.equals("GET") && (matcher = Pattern.compile("/repos/([^/]+)/([^/]+)").matcher(path)).matches()) {
			Published repository = published(matcher.group(1), matcher.group(2));
			send(exchange, repository == null ? 404 : 200, repository == null ? "{\"message\":\"Not Found\"}" : repository.json());
		}
		else {
			send(exchange, 404, "{\"message\":\"Not Found\",\"path\":\"" + path + (query == null ? "" : "?" + query) + "\"}");
		}
	}

	/** GitHub lists at most this many files of a compare, and says nothing of the rest. */
	static final int COMPARE_FILES = 300;

	/** Repositories, by name, whose GraphQL queries GitHub answers with {@code errors} and no data. */
	public final java.util.Set<String> graphqlErrors = Collections.synchronizedSet(new java.util.HashSet<>());

	private static final Pattern GRAPHQL_REPOSITORY = Pattern
		.compile("repository\\(\\s*owner:\\s*\\$(\\w+)\\s*,\\s*name:\\s*\\$(\\w+)\\s*\\)");

	private static final Pattern GRAPHQL_OBJECT = Pattern.compile("(\\w+)\\s*:\\s*object\\(\\s*expression:\\s*\\$(\\w+)\\s*\\)");

	/**
	 * {@code POST /graphql} for {@code repository(owner:, name:)} with aliased {@code object(expression:)}
	 * fields, as GitHub answers it: each expression names a commit, or {@code <commit>:<path>}, resolved
	 * through JGit to its {@code oid}, or {@code null} when there is no such object. Every value must arrive
	 * as a variable: a query with a string literal in its text, or with a repository that is not
	 * {@code repository(owner: $…, name: $…)}, is answered with {@code errors}, as is an unknown repository.
	 */
	private void graphql(HttpExchange exchange, JsonNode body) throws IOException {
		String query = body.path("query").asString("");
		JsonNode variables = body.path("variables");
		Matcher repository = GRAPHQL_REPOSITORY.matcher(query);
		if (query.contains("\"") || !repository.find()) {
			send(exchange, 200, "{\"data\":null,\"errors\":[{\"message\":\"The stub reads values from variables only\"}]}");
			return;
		}
		String owner = variables.path(repository.group(1)).asString("");
		String name = variables.path(repository.group(2)).asString("");
		if (this.graphqlErrors.contains(name)) {
			send(exchange, 200, "{\"data\":null,\"errors\":[{\"message\":\"Something went wrong while executing your query.\"}]}");
			return;
		}
		if (owner.isEmpty() || name.isEmpty() || !Files.isDirectory(this.gitRoot.resolve(owner).resolve(name + ".git"))) {
			send(exchange, 200, GitHubHttp.JSON.writeValueAsString(Map.of("data", Collections.singletonMap("repository", null),
					"errors", List.of(Map.of("type", "NOT_FOUND", "path", List.of("repository"), "message",
							"Could not resolve to a Repository with the name '" + owner + "/" + name + "'.")))));
			return;
		}
		Map<String, Object> objects = new LinkedHashMap<>();
		try (Repository git = repository(owner, name)) {
			Matcher object = GRAPHQL_OBJECT.matcher(query);
			while (object.find()) {
				String oid = objectId(git, variables.path(object.group(2)).asString(""));
				objects.put(object.group(1), oid == null ? null : Map.of("oid", oid));
			}
		}
		send(exchange, 200, GitHubHttp.JSON.writeValueAsString(Map.of("data", Map.of("repository", objects))));
	}

	/** The object {@code expression} names, a commit or {@code <commit>:<path>}, or none. */
	private static @org.jspecify.annotations.Nullable String objectId(Repository git, String expression) throws IOException {
		int colon = expression.indexOf(':');
		String revision = colon < 0 ? expression : expression.substring(0, colon);
		org.eclipse.jgit.lib.ObjectId id;
		try {
			id = revision.isEmpty() ? null : git.resolve(revision);
		}
		catch (org.eclipse.jgit.errors.RevisionSyntaxException ex) {
			return null;
		}
		if (id == null || !git.getObjectDatabase().has(id)) {
			return null;
		}
		try (RevWalk walk = new RevWalk(git)) {
			RevCommit commit = walk.parseCommit(id);
			if (colon < 0) {
				return commit.name();
			}
			try (TreeWalk entry = TreeWalk.forPath(git, expression.substring(colon + 1), commit.getTree())) {
				return entry == null ? null : entry.getObjectId(0).name();
			}
		}
		catch (org.eclipse.jgit.errors.IncorrectObjectTypeException ex) {
			return null;
		}
	}

	long repositoryId(String name) {
		return this.repositories.get(name);
	}

	/** The invitee accepts, on every repository that invited them: a collaborator with the invitation's permission. */
	public void accept(String login) {
		synchronized (this.invitations) {
			this.invitations.forEach((repository, pending) -> {
				String permission = pending.remove(login);
				if (permission != null) {
					collaboratorsOf(repository).put(login, permission);
				}
			});
		}
	}

	/** Requests answered 502, as GitHub down: each a prefix of {@code "METHOD /path"}. */
	public final java.util.Set<String> unavailable = Collections.synchronizedSet(new java.util.HashSet<>());

	/** What {@code DELETE /installation/token} answers: 204, or a failure a test asks for. */
	public volatile int revocationStatus = 204;

	/** The tokens {@code DELETE /installation/token} revoked, in order. */
	public final List<String> revokedTokens = Collections.synchronizedList(new ArrayList<>());

	/** The value of the {@code n}-th installation token of {@link #INSTALLATION}. */
	public volatile java.util.function.IntFunction<String> tokenFormat = n -> "ghs_stubtoken_" + n + "_d41d8cd98f";

	/** GitHub Actions OIDC's key set, served at {@code /.well-known/jwks} when a test names this stub the issuer. */
	public volatile String jwks;

	/**
	 * GitHub Actions runs by ID, as {@code GET /repos/{owner}/{name}/actions/runs/{id}} shows them: each dispatch
	 * adds one, queued, with its title from the workflow's {@code run-name} and the dispatching App's bot as its
	 * triggering actor. A test changes a run to show another one, or completes it ({@link #completeRun}).
	 */
	public final Map<Long, Map<String, Object>> runs = Collections.synchronizedMap(new LinkedHashMap<>());

	/** Whether a dispatch answers 200 with the run's ID ({@code return_run_details}) or 204, as GitHub once did. */
	public volatile boolean runDetails = true;

	/** Whether a workflow's run list shows none yet, as right after a dispatch GitHub has not queued. */
	public volatile boolean runsHidden;

	/** Which mint requests of {@link #INSTALLATION}, by their body, GitHub answers 500. */
	public volatile java.util.function.Predicate<JsonNode> mintRefused = body -> false;

	/** The run is over, with {@code conclusion}. */
	public void completeRun(long id, String conclusion) {
		Map<String, Object> run = this.runs.get(id);
		run.put("status", "completed");
		run.put("conclusion", conclusion);
	}

	/**
	 * {@code POST /repos/{owner}/{name}/actions/workflows/{workflow}/dispatches}: a run of the workflow at the
	 * head of {@code ref}, titled as the published files' {@code run-name} titles it. 404 when the workflow is not
	 * on that branch, as GitHub answers.
	 */
	private void dispatch(HttpExchange exchange, String owner, String name, String workflow, JsonNode body)
			throws IOException {
		String ref = body.path("ref").asString("");
		String path = ".github/workflows/" + workflow;
		String head = Files.isDirectory(this.gitRoot.resolve(owner).resolve(name + ".git")) ? head(owner, name, ref) : null;
		if (head == null || file(owner, name, head, path) == null) {
			send(exchange, 404, "{\"message\":\"Not Found\"}");
			return;
		}
		JsonNode inputs = body.path("inputs");
		String stem = workflow.replaceFirst("\\.ya?ml$", "");
		String operation = inputs.path("operation").asString("");
		long id = this.ids.incrementAndGet();
		Map<String, Object> run = Collections.synchronizedMap(new LinkedHashMap<>());
		run.put("id", id);
		run.put("name", stem);
		run.put("path", path);
		run.put("event", "workflow_dispatch");
		run.put("head_branch", ref);
		run.put("head_sha", head);
		run.put("status", "queued");
		run.put("conclusion", null);
		run.put("display_title", stem + (operation.isEmpty() ? "" : " " + operation) + " "
				+ inputs.path("correlation").asString(""));
		run.put("triggering_actor", Map.of("login", GitHubHttp.JSON.readTree(this.app).path("slug").asString() + "[bot]"));
		run.put("repository_full_name", owner + "/" + name);
		run.put("inputs", GitHubHttp.JSON.convertValue(inputs, Map.class));
		this.runs.put(id, run);
		if (this.runDetails && body.path("return_run_details").asBoolean(false)) {
			send(exchange, 200, GitHubHttp.JSON.writeValueAsString(Map.of("workflow_run_id", id, "run_url",
					apiUrl() + "/repos/" + owner + "/" + name + "/actions/runs/" + id)));
		}
		else {
			send(exchange, 204, "");
		}
	}

	/**
	 * {@code path} at {@code ref} (a branch, or a 40-hex commit) as the contents API shows a file: its blob SHA
	 * and base64 content; none when there is no such file.
	 */
	private @org.jspecify.annotations.Nullable Map<String, Object> file(String owner, String name, String ref, String path)
			throws IOException {
		if (!Files.isDirectory(this.gitRoot.resolve(owner).resolve(name + ".git"))) {
			return null;
		}
		try (Repository repository = repository(owner, name); RevWalk walk = new RevWalk(repository)) {
			var commit = repository.resolve(ref.matches("[0-9a-f]{40}") ? ref : "refs/heads/" + ref);
			if (commit == null || !repository.getObjectDatabase().has(commit)) {
				return null;
			}
			try (TreeWalk tree = TreeWalk.forPath(repository, path, walk.parseCommit(commit).getTree())) {
				if (tree == null || tree.isSubtree()) {
					return null;
				}
				var blob = tree.getObjectId(0);
				byte[] bytes = repository.open(blob).getBytes();
				Map<String, Object> file = new LinkedHashMap<>();
				file.put("type", "file");
				file.put("name", path.substring(path.lastIndexOf('/') + 1));
				file.put("path", path);
				file.put("sha", blob.name());
				file.put("encoding", "base64");
				file.put("content", java.util.Base64.getMimeEncoder().encodeToString(bytes));
				return file;
			}
		}
	}

	/** GitHub numeric IDs of commit authors by email; an author missing here is unresolved, as on GitHub. */
	public final Map<String, Long> userIds = Collections.synchronizedMap(new LinkedHashMap<>());

	/**
	 * A Customer's Merge App installation (Story 8.7): its account, its selection, and whether it is
	 * suspended. Tests change it to show a wider selection or a suspension.
	 */
	public static final class MergeInstallation {

		public final long accountId;

		public final String accountLogin;

		public volatile boolean all;

		public volatile boolean suspended;

		/** Each selected repository as GitHub lists it: {@code id}, {@code full_name}, {@code owner.id}. */
		public final List<Map<String, Object>> repositories = Collections.synchronizedList(new ArrayList<>());

		public MergeInstallation(long accountId, String accountLogin) {
			this.accountId = accountId;
			this.accountLogin = accountLogin;
		}

		/** Selects a repository. */
		public MergeInstallation repository(long id, String fullName, long ownerId) {
			this.repositories.add(Map.of("id", id, "full_name", fullName, "owner", Map.of("id", ownerId)));
			return this;
		}

	}

	/**
	 * Pull requests on repositories outside the organization, a Customer's Source Repository among them
	 * (Story 8.8): by {@code owner/name}, each number's {@code head}, {@code base}, and {@code merged}.
	 */
	public final Map<String, Map<Integer, Map<String, String>>> sourcePulls = Collections.synchronizedMap(new LinkedHashMap<>());

	/** The status a merge on a repository outside the organization answers instead, as a ruleset or an archive would. */
	public volatile Integer sourceMergeRefusal;

	/**
	 * Per organization repository, by name: the status a pull request merge answers instead of merging, such as
	 * 405 (not mergeable) or 422 (validation failed); never the 409 of a moved head branch, which the merge decides.
	 */
	public final Map<String, Integer> mergeRefusals = Collections.synchronizedMap(new LinkedHashMap<>());

	/** The Merge App's installations on Customers' accounts, by installation ID. */
	public final Map<Long, MergeInstallation> mergeInstallations = Collections.synchronizedMap(new LinkedHashMap<>());

	/** The install requests waiting for an organization owner, as {@code GET /app/installation-requests} lists them. */
	public final List<Map<String, Object>> installationRequests = Collections.synchronizedList(new ArrayList<>());

	/** The installations removed through {@code DELETE /app/installations/{id}}. */
	public final List<Long> removedInstallations = Collections.synchronizedList(new ArrayList<>());

	/** Each Merge App token's installation. */
	private final Map<String, Long> tokenInstallations = Collections.synchronizedMap(new LinkedHashMap<>());

	/** The {@code repository_ids} and {@code permissions} a token was minted with, or none for an unknown token. */
	public JsonNode grantOf(String token) {
		JsonNode grant = this.grants.get(token);
		return grant == null ? GitHubHttp.JSON.createObjectNode() : grant;
	}

	/** Every token minted from one Merge App installation, oldest first. */
	public List<String> tokensOf(long installationId) {
		synchronized (this.tokenInstallations) {
			return this.tokenInstallations.entrySet().stream().filter(entry -> entry.getValue() == installationId)
				.map(Map.Entry::getKey).toList();
		}
	}

	/** An install request for an organization, asked by {@code requesterId}. */
	public void installationRequest(long id, long accountId, String accountLogin, long requesterId) {
		this.installationRequests.add(Map.of("id", id, "account", Map.of("id", accountId, "login", accountLogin, "type",
				"Organization"), "requester", Map.of("id", requesterId, "login", "requester-" + requesterId)));
	}

	/** Merged pull requests, as {@code repository#number}. */
	public final java.util.Set<String> mergedPulls = Collections.synchronizedSet(new java.util.LinkedHashSet<>());

	/** Per repository: each pull request's head branch, open or closed, by number. */
	public final Map<String, Map<Integer, String>> pullHeads = Collections.synchronizedMap(new LinkedHashMap<>());

	/** Each pull request the System opened, as {@code repository#number}, with its title now. */
	public final Map<String, String> pullTitles = Collections.synchronizedMap(new LinkedHashMap<>());

	/** The comments on each pull request's conversation, as {@code repository#number}, oldest first. */
	public final Map<String, List<String>> pullComments = Collections.synchronizedMap(new LinkedHashMap<>());

	private final AtomicInteger pullNumbers = new AtomicInteger(100);

	/** The repositories deleted, by name. */
	public final List<String> deleted = Collections.synchronizedList(new ArrayList<>());

	/** Invitation IDs by login, as the invitations list shows them. */
	private final Map<Long, String> invitationIds = Collections.synchronizedMap(new LinkedHashMap<>());

	private long invitationId(String login) {
		synchronized (this.invitationIds) {
			return this.invitationIds.entrySet()
				.stream()
				.filter(entry -> entry.getValue().equals(login))
				.map(Map.Entry::getKey)
				.findFirst()
				.orElseGet(() -> {
					long id = this.ids.incrementAndGet();
					this.invitationIds.put(id, login);
					return id;
				});
		}
	}

	/** The login of the GitHub account with this ID now. */
	public String loginOf(long id) {
		return this.logins.getOrDefault(id, "user-" + id);
	}

	/**
	 * The account with this ID renames itself on GitHub: its pending invitations, its collaborator places,
	 * its organization membership, and its two-factor listing follow it to the new login, as on GitHub.
	 */
	public void renameUser(long id, String login) {
		String old = loginOf(id);
		this.logins.put(id, login);
		synchronized (this.invitations) {
			this.invitations.values().forEach(pending -> move(pending, old, login));
		}
		synchronized (this.collaborators) {
			this.collaborators.values().forEach(placed -> move(placed, old, login));
		}
		synchronized (this.invitationIds) {
			this.invitationIds.replaceAll((invitation, invitee) -> invitee.equals(old) ? login : invitee);
		}
		if (this.members.remove(old)) {
			this.members.add(login);
		}
		if (this.withoutTwoFactor.remove(old.toLowerCase(java.util.Locale.ROOT))) {
			this.withoutTwoFactor.add(login.toLowerCase(java.util.Locale.ROOT));
		}
	}

	private static void move(Map<String, String> byLogin, String from, String to) {
		String value = byLogin.remove(from);
		if (value != null) {
			byLogin.put(to, value);
		}
	}

	/** A user as GitHub lists one: the login, and the ID of the account that carries it now, if any. */
	private Map<String, Object> user(String login) {
		Map<String, Object> user = new LinkedHashMap<>();
		user.put("login", login);
		Long id = idOf(login);
		if (id != null) {
			user.put("id", id);
		}
		return user;
	}

	private @org.jspecify.annotations.Nullable Long idOf(String login) {
		synchronized (this.logins) {
			for (Map.Entry<Long, String> entry : this.logins.entrySet()) {
				if (entry.getValue().equalsIgnoreCase(login)) {
					return entry.getKey();
				}
			}
		}
		Matcher matcher = Pattern.compile("user-(\\d+)", Pattern.CASE_INSENSITIVE).matcher(login);
		if (matcher.matches()) {
			long id = Long.parseLong(matcher.group(1));
			// Renamed away: the old login no longer names the account.
			return this.logins.containsKey(id) ? null : id;
		}
		return null;
	}

	/** A direct collaborator someone added outside vibefix. */
	public void addCollaborator(String repository, String login, String role) {
		collaboratorsOf(repository).put(login, role);
	}

	/** A pending invitation someone sent outside vibefix. */
	public void addInvitation(String repository, String login, String permission) {
		invitationsOf(repository).put(login, permission);
	}

	private Map<String, String> collaboratorsOf(String repository) {
		return this.collaborators.computeIfAbsent(repository, name -> Collections.synchronizedMap(new LinkedHashMap<>()));
	}

	private Map<String, String> invitationsOf(String repository) {
		return this.invitations.computeIfAbsent(repository, name -> Collections.synchronizedMap(new LinkedHashMap<>()));
	}

	/** The {@code repository_ids} and {@code permissions} of the request's token, or none for another credential. */
	JsonNode grant(HttpExchange exchange) {
		String authorization = exchange.getRequestHeaders().getFirst("Authorization");
		JsonNode grant = authorization == null ? null : this.grants.get(authorization.replaceFirst("^Bearer ", ""));
		return grant == null ? GitHubHttp.JSON.createObjectNode() : grant;
	}

	/** Whether the request's token reads contents, as a private repository's refs and pull requests need. */
	private boolean readsContents(HttpExchange exchange) {
		String contents = grant(exchange).path("permissions").path("contents").asString("");
		return contents.equals("read") || contents.equals("write");
	}

	/** Whether the request's token reads metadata or contents, as reading a private repository needs. */
	private boolean readsMetadata(HttpExchange exchange) {
		String metadata = grant(exchange).path("permissions").path("metadata").asString("");
		return metadata.equals("read") || metadata.equals("write") || readsContents(exchange);
	}

	/** An organization repository as {@code GET /repos/{org}/{name}} shows it, with its settings. */
	private String organizationRepository(String name, long id) {
		Map<String, Object> shown = new LinkedHashMap<>();
		shown.put("id", id);
		shown.put("name", name);
		shown.put("full_name", ORGANIZATION + "/" + name);
		shown.put("default_branch", "main");
		shown.putAll(this.settings.getOrDefault(name, Map.of()));
		return GitHubHttp.JSON.writeValueAsString(shown);
	}

	/** A delivery as GitHub lists it: one attempt of one GUID. */
	static Map<String, Object> delivery(long id, String guid, String deliveredAt, String status) {
		return Map.of("id", id, "guid", guid, "delivered_at", deliveredAt, "status", status, "event", "push");
	}

	private static String parameter(String query, String name, String fallback) {
		if (query != null) {
			for (String pair : query.split("&")) {
				if (pair.startsWith(name + "=")) {
					return pair.substring(name.length() + 1);
				}
			}
		}
		return fallback;
	}

	static void send(HttpExchange exchange, int status, String body) throws IOException {
		send(exchange, status, body, null);
	}

	private static void send(HttpExchange exchange, int status, String body, String link) throws IOException {
		byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
		exchange.getResponseHeaders().add("Content-Type", "application/json");
		if (link != null) {
			exchange.getResponseHeaders().add("Link", link);
		}
		exchange.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
		if (bytes.length > 0) {
			try (OutputStream out = exchange.getResponseBody()) {
				out.write(bytes);
			}
		}
		exchange.close();
	}

}
