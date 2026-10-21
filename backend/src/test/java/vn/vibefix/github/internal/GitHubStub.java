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
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.treewalk.TreeWalk;

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
final class GitHubStub implements AutoCloseable {

	static final String ORGANIZATION = "vibefix-dev";

	static final long INSTALLATION = 7;

	/** One request as GitHub would have received it. */
	record Seen(String method, String path, String authorization, JsonNode body) {
	}

	final List<Seen> seen = Collections.synchronizedList(new ArrayList<>());

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

	/** Pending invitations by invitee, with their permission, and collaborators who accepted. */
	private final Map<String, String> invitations = new LinkedHashMap<>();

	private final Map<String, String> collaborators = new LinkedHashMap<>();

	GitHubStub(Path gitRoot) throws IOException {
		this.gitRoot = gitRoot;
		this.server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		this.server.createContext("/", this::handle);
		this.server.start();
	}

	URI apiUrl() {
		return URI.create("http://127.0.0.1:" + this.server.getAddress().getPort());
	}

	URI gitUrl() {
		return this.gitRoot.toUri();
	}

	/** A public source repository whose default branch holds {@code .github/workflows/ci.yml}. */
	void source(String owner, String name) throws Exception {
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

	Repository repository(String owner, String name) throws IOException {
		return Git.open(this.gitRoot.resolve(owner).resolve(name + ".git").toFile()).getRepository();
	}

	boolean hasFile(String owner, String name, String branch, String path) throws IOException {
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

	private void handle(HttpExchange exchange) throws IOException {
		String method = exchange.getRequestMethod();
		String path = exchange.getRequestURI().getPath();
		String query = exchange.getRequestURI().getQuery();
		String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
		JsonNode json = body.isBlank() ? GitHubHttp.JSON.createObjectNode() : GitHubHttp.JSON.readTree(body);
		this.seen.add(new Seen(method, path, exchange.getRequestHeaders().getFirst("Authorization"), json));
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
			send(exchange, 200, "{\"id\":" + INSTALLATION + ",\"repository_selection\":\"selected\"}");
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
		else if ((matcher = Pattern.compile("/repos/" + ORGANIZATION + "/[^/]+/collaborators/([^/]+)/permission").matcher(path))
			.matches()) {
			String permission = this.collaborators.getOrDefault(matcher.group(1), "none");
			send(exchange, 200, "{\"permission\":\"" + permission + "\",\"role_name\":\"" + permission + "\"}");
		}
		else if ((matcher = Pattern.compile("/repos/" + ORGANIZATION + "/[^/]+/collaborators/([^/]+)").matcher(path)).matches()) {
			String login = matcher.group(1);
			String permission = body.path("permission").asString("").equals("push") ? "write" : "read";
			if (method.equals("GET")) {
				send(exchange, this.collaborators.containsKey(login) ? 204 : 404, "");
			}
			else if (this.collaborators.containsKey(login)) {
				this.collaborators.put(login, permission);
				send(exchange, 204, "");
			}
			else {
				// A pending invitation keeps the permission it was sent with.
				this.invitations.putIfAbsent(login, permission);
				send(exchange, 201, "{\"id\":1}");
			}
		}
		else if (Pattern.compile("/repos/" + ORGANIZATION + "/[^/]+/invitations").matcher(path).matches()) {
			send(exchange, 200, GitHubHttp.JSON.writeValueAsString(this.invitations.entrySet()
				.stream()
				.map(invitation -> Map.of("invitee", Map.of("login", invitation.getKey()), "permissions", invitation.getValue()))
				.toList()));
		}
		else if ((matcher = Pattern.compile("/repos/" + ORGANIZATION + "/([^/]+)").matcher(path)).matches()) {
			Long id = this.repositories.get(matcher.group(1));
			send(exchange, id == null ? 404 : 200, id == null ? "{\"message\":\"Not Found\"}" : "{\"id\":" + id + "}");
		}
		else {
			send(exchange, 404, "{\"message\":\"Not Found\",\"path\":\"" + path + (query == null ? "" : "?" + query) + "\"}");
		}
	}

	long repositoryId(String name) {
		return this.repositories.get(name);
	}

	/** The invitee accepts: a collaborator with the invitation's permission, and no invitation left. */
	void accept(String login) {
		this.collaborators.put(login, this.invitations.remove(login));
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
