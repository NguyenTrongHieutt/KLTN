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
 * tokens, organization reads, repository creation, rulesets, forks that appear after one poll, and
 * invitations. It records every request and every token it handed out. As GitHub does (Spike S4,
 * 2026-10-21), it shows a ruleset's {@code bypass_actors} only to a token with Administration write,
 * refuses a fork to a token narrowed by {@code repository_ids}, and leaves a pending invitation at
 * the permission it was sent with. It also answers an App's webhook reads: its deliveries, newest first
 * and paged by {@code cursor} in the {@code Link} header, their redelivery, and the webhook's
 * configuration.
 */
public final class GitHubStub implements AutoCloseable {

	public static final String ORGANIZATION = "vibefix-dev";

	public static final long INSTALLATION = 7;

	/** One request as GitHub would have received it. */
	public record Seen(String method, String path, String authorization, JsonNode body) {
	}

	public final List<Seen> seen = Collections.synchronizedList(new ArrayList<>());

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

	private final Map<String, List<JsonNode>> rulesets = new LinkedHashMap<>();

	private final Map<String, Integer> forkPolls = new LinkedHashMap<>();

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

	/** Repositories whose Issues are on, as {@code GET /repos/{org}/{name}} shows them. */
	public final java.util.Set<String> issuesOn = Collections.synchronizedSet(new java.util.HashSet<>());

	/** Organization members: GitHub adds them at once, without an invitation (AD-11's MEMBER_DRIFT). */
	public final java.util.Set<String> members = Collections.synchronizedSet(new java.util.HashSet<>());

	/** Outside collaborators without two-factor authentication, as {@code filter=2fa_disabled} lists them. */
	public final java.util.Set<String> withoutTwoFactor = Collections.synchronizedSet(new java.util.HashSet<>());

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

	/** The names of the rulesets on {@code name} in the organization. */
	public List<String> rulesetNames(String name) {
		return this.rulesets.getOrDefault(name, List.of()).stream().map(ruleset -> ruleset.path("name").asString()).toList();
	}

	/** The ID GitHub gave {@code name} in the organization. */
	public Long organizationRepositoryId(String name) {
		return this.repositories.get(name);
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
		Path work = Files.createTempDirectory("stub-push-");
		try (Git git = Git.cloneRepository().setURI(this.gitRoot.resolve(owner).resolve(name + ".git").toUri().toString())
			.setDirectory(work.toFile()).setBranch(branch).call()) {
			Path target = work.resolve(file);
			Files.createDirectories(target.getParent());
			Files.writeString(target, content);
			git.add().addFilepattern(".").call();
			String author = authorEmail.substring(0, authorEmail.indexOf('@'));
			RevCommit commit = git.commit().setMessage(message).setAuthor(author, authorEmail)
				.setCommitter(author, authorEmail).call();
			git.push().call();
			return commit.name();
		}
	}

	/** A platform repository as Setup leaves it: a bare copy of {@code fromOwner/fromName}, with its ID. */
	public long platformRepository(String name, String fromOwner, String fromName) throws Exception {
		Git.cloneRepository().setURI(this.gitRoot.resolve(fromOwner).resolve(fromName + ".git").toUri().toString()).setBare(true)
			.setDirectory(this.gitRoot.resolve(ORGANIZATION).resolve(name + ".git").toFile()).call().close();
		long id = this.ids.incrementAndGet();
		this.repositories.put(name, id);
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
		this.seen.add(new Seen(method, path, exchange.getRequestHeaders().getFirst("Authorization"), json));
		if (this.unavailable.stream().anyMatch(prefix -> (method + " " + path).startsWith(prefix))) {
			send(exchange, 502, "{\"message\":\"Bad Gateway\"}");
			return;
		}
		try {
			route(exchange, method, path, query, json);
		}
		catch (Exception ex) {
			send(exchange, 500, "{\"message\":\"" + ex.getClass().getSimpleName() + "\"}");
		}
	}

	private void route(HttpExchange exchange, String method, String path, String query, JsonNode body) throws Exception {
		Matcher matcher;
		if (method.equals("POST") && path.equals("/app/installations/" + INSTALLATION + "/access_tokens")) {
			String token = "ghs_stubtoken_" + this.tokens.size() + "_d41d8cd98f";
			this.tokens.add(token);
			this.grants.put(token, body);
			send(exchange, 201, "{\"token\":\"" + token + "\",\"expires_at\":\"2026-10-19T04:00:00Z\"}");
		}
		else if (method.equals("DELETE") && path.equals("/installation/token")) {
			this.revoked.incrementAndGet();
			send(exchange, 204, "");
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
					+ "\",\"two_factor_requirement_enabled\":true,\"default_repository_permission\":\"none\"}");
		}
		else if (method.equals("GET") && path.equals("/orgs/" + ORGANIZATION + "/actions/permissions")) {
			send(exchange, 200, "{\"enabled_repositories\":\"none\"}");
		}
		else if (method.equals("POST") && path.equals("/orgs/" + ORGANIZATION + "/repos")) {
			String name = body.path("name").asString();
			Git.init().setBare(true).setInitialBranch("main")
				.setDirectory(this.gitRoot.resolve(ORGANIZATION).resolve(name + ".git").toFile()).call().close();
			long id = this.ids.incrementAndGet();
			this.repositories.put(name, id);
			send(exchange, 201, "{\"id\":" + id + ",\"name\":\"" + name + "\"}");
		}
		else if ((matcher = Pattern.compile("/repos/" + ORGANIZATION + "/([^/]+)/contents/\\.github/workflows").matcher(path))
			.matches()) {
			boolean found = hasFile(ORGANIZATION, matcher.group(1), "main", ".github/workflows/ci.yml");
			send(exchange, found ? 200 : 404, found ? "[{\"name\":\"ci.yml\"}]" : "{\"message\":\"Not Found\"}");
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
		else if ((matcher = Pattern.compile("/repos/" + ORGANIZATION + "/([^/]+)/rulesets").matcher(path)).matches()) {
			List<JsonNode> list = this.rulesets.computeIfAbsent(matcher.group(1), name -> new ArrayList<>());
			if (method.equals("POST")) {
				long id = this.ids.incrementAndGet();
				((tools.jackson.databind.node.ObjectNode) body).put("id", id);
				list.add(body);
				send(exchange, 201, GitHubHttp.JSON.writeValueAsString(body));
			}
			else {
				send(exchange, 200, GitHubHttp.JSON.writeValueAsString(list.stream()
					.map(ruleset -> Map.of("id", ruleset.path("id").asLong(), "name", ruleset.path("name").asString()))
					.toList()));
			}
		}
		else if ((matcher = Pattern.compile("/repos/" + ORGANIZATION + "/([^/]+)/rulesets/(\\d+)").matcher(path)).matches()) {
			String id = matcher.group(2);
			JsonNode ruleset = this.rulesets.getOrDefault(matcher.group(1), List.of())
				.stream()
				.filter(found -> found.path("id").asString().equals(id))
				.findFirst()
				.orElseThrow();
			tools.jackson.databind.node.ObjectNode shown = (tools.jackson.databind.node.ObjectNode) ruleset.deepCopy();
			if (!grant(exchange).path("permissions").path("administration").asString("").equals("write")) {
				shown.remove("bypass_actors");
			}
			send(exchange, 200, GitHubHttp.JSON.writeValueAsString(shown));
		}
		else if ((matcher = Pattern.compile("/repos/" + ORGANIZATION + "/([^/]+)/forks").matcher(path)).matches()) {
			if (grant(exchange).has("repository_ids")) {
				send(exchange, 403, "{\"message\":\"Resource not accessible by integration\"}");
				return;
			}
			String forkName = body.path("name").asString();
			Git.cloneRepository().setURI(this.gitRoot.resolve(ORGANIZATION).resolve(matcher.group(1) + ".git").toUri().toString())
				.setBare(true).setDirectory(this.gitRoot.resolve(ORGANIZATION).resolve(forkName + ".git").toFile()).call().close();
			long id = this.ids.incrementAndGet();
			this.repositories.put(forkName, id);
			send(exchange, 202, "{\"id\":" + id + ",\"name\":\"" + forkName + "\"}");
		}
		else if ((matcher = Pattern.compile("/repos/" + ORGANIZATION + "/([^/]+)/commits").matcher(path)).matches()) {
			// A fork appears after one poll, as GitHub creates forks asynchronously.
			int polls = this.forkPolls.merge(matcher.group(1), 1, Integer::sum);
			send(exchange, polls < 2 ? 409 : 200, polls < 2 ? "{\"message\":\"Git Repository is empty.\"}" : "[{\"sha\":\"abc\"}]");
		}
		else if ((matcher = Pattern.compile("/repos/" + ORGANIZATION + "/([^/]+)/collaborators/([^/]+)/permission").matcher(path))
			.matches()) {
			String permission = collaboratorsOf(matcher.group(1)).getOrDefault(matcher.group(2), "none");
			send(exchange, 200, "{\"permission\":\"" + permission + "\",\"role_name\":\"" + permission + "\"}");
		}
		else if (method.equals("GET") && path.equals("/orgs/" + ORGANIZATION + "/outside_collaborators")) {
			List<Map<String, String>> list = new ArrayList<>();
			if (query != null && query.contains("filter=2fa_disabled")) {
				this.withoutTwoFactor.forEach(login -> list.add(Map.of("login", login)));
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
				.map(collaborator -> Map.of("login", collaborator.getKey(), "role_name", collaborator.getValue()))
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
				.map(pull -> Map.of("number", pull.getKey(), "user", Map.of("login", pull.getValue())))
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
			boolean merged = this.mergedPulls.contains(matcher.group(1) + "#" + matcher.group(2));
			send(exchange, 200, "{\"number\":" + matcher.group(2) + ",\"merged\":" + merged + "}");
		}
		else if (method.equals("PATCH")
				&& (matcher = Pattern.compile("/repos/" + ORGANIZATION + "/([^/]+)/pulls/(\\d+)").matcher(path)).matches()) {
			this.pulls.getOrDefault(matcher.group(1), new LinkedHashMap<>()).remove(Integer.parseInt(matcher.group(2)));
			this.closedPulls.add(matcher.group(1) + "#" + matcher.group(2));
			send(exchange, 200, "{\"state\":\"closed\"}");
		}
		else if (method.equals("GET") && (matcher = Pattern.compile("/repos/" + ORGANIZATION + "/([^/]+)/events").matcher(path))
			.matches()) {
			send(exchange, 200, GitHubHttp.JSON.writeValueAsString(this.eventActors.getOrDefault(matcher.group(1), List.of())
				.stream()
				.map(login -> Map.of("type", "PushEvent", "actor", Map.of("login", login)))
				.toList()));
		}
		else if ((matcher = Pattern.compile("/repos/" + ORGANIZATION + "/([^/]+)").matcher(path)).matches()) {
			Long id = this.repositories.get(matcher.group(1));
			send(exchange, id == null ? 404 : 200, id == null ? "{\"message\":\"Not Found\"}"
					: "{\"id\":" + id + ",\"name\":\"" + matcher.group(1) + "\",\"has_wiki\":false,\"has_issues\":"
							+ this.issuesOn.contains(matcher.group(1)) + ",\"has_projects\":false,\"has_discussions\":false}");
		}
		else if (method.equals("GET") && (matcher = Pattern.compile("/repositories/(\\d+)").matcher(path)).matches()) {
			Published repository = this.published.get(Long.parseLong(matcher.group(1)));
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
				send(exchange, 200, GitHubHttp.JSON.writeValueAsString(Map.of("ahead_by", commits.size(), "total_commits",
						commits.size(), "commits", commits, "files", files)));
			}
		}
		else if (method.equals("GET") && (matcher = Pattern.compile("/repos/([^/]+)/([^/]+)").matcher(path)).matches()) {
			Published repository = published(matcher.group(1), matcher.group(2));
			send(exchange, repository == null ? 404 : 200, repository == null ? "{\"message\":\"Not Found\"}" : repository.json());
		}
		else {
			send(exchange, 404, "{\"message\":\"Not Found\",\"path\":\"" + path + (query == null ? "" : "?" + query) + "\"}");
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

	/** GitHub numeric IDs of commit authors by email; an author missing here is unresolved, as on GitHub. */
	public final Map<String, Long> userIds = Collections.synchronizedMap(new LinkedHashMap<>());

	/** Merged pull requests, as {@code repository#number}. */
	public final java.util.Set<String> mergedPulls = Collections.synchronizedSet(new java.util.LinkedHashSet<>());

	/** Per repository: each pull request's head branch, open or closed, by number. */
	public final Map<String, Map<Integer, String>> pullHeads = Collections.synchronizedMap(new LinkedHashMap<>());

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

	/** A direct collaborator someone added outside vibefix. */
	public void addCollaborator(String repository, String login, String role) {
		collaboratorsOf(repository).put(login, role);
	}

	private Map<String, String> collaboratorsOf(String repository) {
		return this.collaborators.computeIfAbsent(repository, name -> Collections.synchronizedMap(new LinkedHashMap<>()));
	}

	private Map<String, String> invitationsOf(String repository) {
		return this.invitations.computeIfAbsent(repository, name -> Collections.synchronizedMap(new LinkedHashMap<>()));
	}

	private JsonNode grant(HttpExchange exchange) {
		String authorization = exchange.getRequestHeaders().getFirst("Authorization");
		JsonNode grant = authorization == null ? null : this.grants.get(authorization.replaceFirst("^Bearer ", ""));
		return grant == null ? GitHubHttp.JSON.createObjectNode() : grant;
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

	private static void send(HttpExchange exchange, int status, String body) throws IOException {
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
