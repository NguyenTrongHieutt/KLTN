package vn.vibefix.github.internal;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import javax.crypto.Cipher;

import org.jspecify.annotations.Nullable;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Spike S6's stand-in for GitHub, on loopback: three Apps (Platform, Source, Runner), each told apart by
 * its JWT's issuer and installed where the design puts it; installation tokens checked against the
 * installation, their {@code repository_ids} and their permissions; private repositories with branches
 * as lists of commits; the runner's workflow dispatches, runs, artifacts behind a redirect to "blob
 * storage", timings, and deletions; releases; invitations; and pull requests. A dispatched run does its
 * work at once: it unseals its inputs with the runner's private key, as the workflow does, and copies
 * or scans only what the unsealed tokens may reach; a scan's result is uploaded sealed under the
 * dispatch's {@code result_key}, as {@code s6-scan} does. It records every request, every token, every
 * revocation, every uploaded result, and the order of runs completing and tokens being revoked.
 */
final class SpikeS6Stub implements AutoCloseable {

	static final String ORGANIZATION = "vibefix-dev";

	static final long PLATFORM_APP = 123456;

	static final long SOURCE_APP = 2222;

	static final long RUNNER_APP = 3333;

	static final long PLATFORM_INSTALLATION = 7;

	static final long SOURCE_INSTALLATION = 21;

	static final long RUNNER_INSTALLATION = 31;

	/** The Customer's private Source Repository. */
	static final String SOURCE = "lan-shop/quan-may-private";

	static final String RUNNER = "vibefix-runner-dev/vibefix-runner";

	/** The Customer, admin of {@link #SOURCE}. */
	static final String CUSTOMER = "lan-shop";

	static final String FREE_RULESET = "Upgrade to GitHub Pro or make this repository public to enable this feature.";

	/** One request as GitHub would have received it. */
	record Seen(String method, String path, @Nullable String query, @Nullable String authorization, JsonNode body) {
	}

	/** What a token was minted for. */
	record Grant(long installation, Set<Long> repositoryIds, Map<String, String> permissions) {
	}

	final List<Seen> seen = Collections.synchronizedList(new ArrayList<>());

	/** Every token handed out, oldest first. */
	final List<String> tokens = Collections.synchronizedList(new ArrayList<>());

	final Map<String, Grant> grants = Collections.synchronizedMap(new LinkedHashMap<>());

	final Set<String> revoked = Collections.synchronizedSet(new HashSet<>());

	/** {@code completed <run>} and {@code revoked <token>}, in the order they happened. */
	final List<String> events = Collections.synchronizedList(new ArrayList<>());

	/** The dispatch bodies, as the runner received them. */
	final List<JsonNode> dispatches = Collections.synchronizedList(new ArrayList<>());

	/** The Authorization header of each blob-storage request, {@code none} when there was none. */
	final List<String> blobAuthorizations = Collections.synchronizedList(new ArrayList<>());

	final List<Long> deletedArtifacts = Collections.synchronizedList(new ArrayList<>());

	final List<Long> deletedLogs = Collections.synchronizedList(new ArrayList<>());

	/** Pull request comments, as {@code owner/name#number}. */
	final List<String> comments = Collections.synchronizedList(new ArrayList<>());

	/** 200 with the run's ID when the dispatch asks for {@code return_run_details}; 204 for GitHub's old answer. */
	volatile int dispatchStatus = 200;

	/** How many reads of a run answer {@code queued} before it is {@code completed}. */
	volatile int pollsBeforeCompleted = 1;

	volatile boolean neverCompletes;

	/** A copy run fails at this step ({@code fetch}, {@code check}, {@code push}). */
	volatile @Nullable String failAtStep;

	/** Whether GitHub marks an open pull request merged once a merge brings its head commit in. */
	volatile boolean containedPullsMarkedMerged;

	/** The scan's envelope arrives with a changed tag. */
	volatile boolean corruptScanTag;

	/** Each run's {@code result.json} as uploaded: plain for a copy, the envelope for a scan. */
	final List<String> uploadedResults = Collections.synchronizedList(new ArrayList<>());

	/** The scan's envelope arrives with a plaintext {@code output} beside its four fields. */
	volatile boolean plaintextBesideEnvelope;

	/** How many dispatches answer 404 first, as GitHub does for a workflow file it does not know yet. */
	volatile int dispatchNotFound;

	/** Whether a dispatch naming {@code return_run_details} is refused with 422, as an API without the field does. */
	volatile boolean refuseReturnRunDetails;

	/** What {@code DELETE /installation/token} answers. */
	volatile int revocationStatus = 204;

	/** What {@code DELETE …/actions/artifacts/{id}} answers. */
	volatile int artifactDeleteStatus = 204;

	/** Another {@code upload_url} for the release, such as a foreign host. */
	volatile @Nullable String uploadUrl;

	/** What a ruleset on a private repository is answered with, status and message. */
	volatile int rulesetStatus = 403;

	volatile String rulesetMessage = FREE_RULESET;

	/** {@code /timing} without {@code run_duration_ms}, or without {@code billable}. */
	volatile boolean timingWithoutDuration;

	volatile boolean timingWithoutBillable;

	/** The runs cancelled through {@code POST …/actions/runs/{id}/cancel}. */
	final List<Long> cancelled = Collections.synchronizedList(new ArrayList<>());

	/** The Authorization header of each logs-storage request, {@code none} when there was none. */
	final List<String> logAuthorizations = Collections.synchronizedList(new ArrayList<>());

	/** The lines of a failed copy step's log: line 1 to 60, the last naming the refusal. */
	static final int LOG_LINES = 60;

	private final PrivateKey runnerKey;

	private final HttpServer server;

	private final AtomicLong ids = new AtomicLong(5000);

	private final AtomicLong commits = new AtomicLong();

	private final Map<String, Repo> repositories = Collections.synchronizedMap(new LinkedHashMap<>());

	private final Map<Long, Run> runs = Collections.synchronizedMap(new LinkedHashMap<>());

	private final Map<Long, Artifact> artifacts = Collections.synchronizedMap(new LinkedHashMap<>());

	/** A repository: its branches as commit lists (head last), files, people, pull requests, and releases. */
	static final class Repo {

		final long id;

		final String fullName;

		volatile boolean privateRepository;

		volatile String defaultBranch = "main";

		final Map<String, List<String>> branches = Collections.synchronizedMap(new LinkedHashMap<>());

		final Map<String, String> files = Collections.synchronizedMap(new LinkedHashMap<>());

		final Map<String, String> collaborators = Collections.synchronizedMap(new LinkedHashMap<>());

		final Map<String, String> invitations = Collections.synchronizedMap(new LinkedHashMap<>());

		final Map<Integer, Pull> pulls = Collections.synchronizedMap(new LinkedHashMap<>());

		final Map<String, Release> releases = Collections.synchronizedMap(new LinkedHashMap<>());

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

	static final class Release {

		final long id;

		final List<Map<String, Object>> assets = Collections.synchronizedList(new ArrayList<>());

		Release(long id) {
			this.id = id;
		}

	}

	private static final class Run {

		final long id;

		final String workflow;

		final String title;

		volatile int polls;

		volatile boolean completed;

		volatile String conclusion = "success";

		final List<String> unsealed = new ArrayList<>();

		Run(long id, String workflow, String correlation) {
			this.id = id;
			this.workflow = workflow;
			this.title = workflow.replace(".yml", "") + " " + correlation;
		}

	}

	private record Artifact(long id, long runId, String name, byte[] zip) {
	}

	SpikeS6Stub(PrivateKey runnerKey) throws IOException {
		this.runnerKey = runnerKey;
		Repo source = add(SOURCE, 9001, true);
		source.branches.put("main", new ArrayList<>(List.of(commit(), commit())));
		source.collaborators.put(CUSTOMER, "admin");
		Repo runner = add(RUNNER, 9002, true);
		runner.branches.put("main", new ArrayList<>(List.of(commit())));
		this.server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		this.server.createContext("/", this::handle);
		this.server.start();
	}

	URI apiUrl() {
		return URI.create("http://127.0.0.1:" + this.server.getAddress().getPort());
	}

	@Override
	public void close() {
		this.server.stop(0);
	}

	Repo repository(String fullName) {
		return this.repositories.get(fullName.toLowerCase(Locale.ROOT));
	}

	@Nullable String head(String fullName, String branch) {
		Repo repo = repository(fullName);
		List<String> list = repo == null ? null : repo.branches.get(branch);
		return list == null || list.isEmpty() ? null : list.getLast();
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

	/** The run IDs, with the tokens each one unsealed. */
	Map<Long, List<String>> unsealedByRun() {
		Map<Long, List<String>> unsealed = new LinkedHashMap<>();
		synchronized (this.runs) {
			this.runs.forEach((id, run) -> unsealed.put(id, List.copyOf(run.unsealed)));
		}
		return unsealed;
	}

	private Repo add(String fullName, long id, boolean privateRepository) {
		Repo repo = new Repo(id, fullName, privateRepository);
		this.repositories.put(fullName.toLowerCase(Locale.ROOT), repo);
		return repo;
	}

	private String commit() {
		try {
			return HexFormat.of()
				.formatHex(MessageDigest.getInstance("SHA-1")
					.digest(("commit-" + this.commits.incrementAndGet()).getBytes(StandardCharsets.UTF_8)));
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
			body = raw.length == 0 || path.startsWith("/uploads/") ? GitHubHttp.JSON.createObjectNode() : GitHubHttp.JSON.readTree(raw);
		}
		catch (RuntimeException ex) {
			body = GitHubHttp.JSON.createObjectNode();
		}
		Seen request = new Seen(method, path, query, exchange.getRequestHeaders().getFirst("Authorization"), body);
		this.seen.add(request);
		try {
			route(exchange, request, raw);
		}
		catch (Exception ex) {
			send(exchange, 500, Map.of("message", ex.getClass().getSimpleName()));
		}
	}

	private void route(HttpExchange exchange, Seen request, byte[] raw) throws Exception {
		String method = request.method();
		String path = request.path();
		JsonNode body = request.body();
		Matcher matcher;
		if (path.startsWith("/blob/")) {
			this.blobAuthorizations.add(request.authorization() == null ? "none" : request.authorization());
			Artifact artifact = this.artifacts.get(Long.parseLong(path.substring("/blob/".length())));
			if (artifact == null || request.query() == null || !request.query().contains("sig=")) {
				send(exchange, 403, Map.of("message", "AuthenticationFailed"));
				return;
			}
			sendBytes(exchange, artifact.zip());
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
					"repository_selection", installation == PLATFORM_INSTALLATION ? "all" : "selected"));
		}
		else if (method.equals("POST") && (matcher = match("/app/installations/(\\d+)/access_tokens", path)) != null) {
			long installation = Long.parseLong(matcher.group(1));
			if (appOf(installation) == 0 || appOf(installation) != issuer(request)) {
				send(exchange, 404, Map.of("message", "Not Found"));
				return;
			}
			Set<Long> ids = new HashSet<>();
			body.path("repository_ids").forEach(id -> ids.add(id.asLong()));
			for (long id : ids) {
				if (this.repositories.values().stream().noneMatch(repo -> repo.id == id && installationCovers(installation, repo))) {
					send(exchange, 422, Map.of("message", "There is at least one repository that does not exist or is not accessible to the parent installation."));
					return;
				}
			}
			Map<String, String> permissions = new LinkedHashMap<>();
			body.path("permissions").properties().forEach(entry -> permissions.put(entry.getKey(), entry.getValue().asString()));
			Map<String, String> ceiling = ceiling(installation);
			for (Map.Entry<String, String> wanted : permissions.entrySet()) {
				String granted = ceiling.get(wanted.getKey());
				if (granted == null || (wanted.getValue().equals("write") && !granted.equals("write"))) {
					send(exchange, 422, Map.of("message", "The permissions requested are not granted to this installation."));
					return;
				}
			}
			String token = "ghs_" + "s6stub" + this.tokens.size() + "_" + installation + "_k3y";
			this.tokens.add(token);
			this.grants.put(token, new Grant(installation, Set.copyOf(ids), Map.copyOf(permissions)));
			send(exchange, 201, Map.of("token", token, "expires_at", "2026-11-09T04:00:00Z"));
		}
		else if (method.equals("DELETE") && path.equals("/installation/token")) {
			String token = bearer(request);
			if (this.revocationStatus != 204) {
				send(exchange, this.revocationStatus, Map.of("message", "Server Error"));
				return;
			}
			if (token != null) {
				this.revoked.add(token);
				this.events.add("revoked " + token);
			}
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
			send(exchange, 200, Map.of("enabled_repositories", "none"));
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
			repositoryRoute(exchange, request, repository(matcher.group(1)), matcher.group(2), raw);
		}
		else if ((matcher = match("/uploads/repos/([^/]+/[^/]+)/releases/(\\d+)/assets", path)) != null) {
			Repo repo = repository(matcher.group(1));
			if (repo == null || refuse(exchange, request, repo, "contents", "write")) {
				return;
			}
			long releaseId = Long.parseLong(matcher.group(2));
			Release release = repo.releases.values().stream().filter(found -> found.id == releaseId).findFirst().orElse(null);
			String name = request.query() == null ? "" : request.query().replaceFirst("^name=", "");
			if (release == null || name.isEmpty()) {
				send(exchange, 404, Map.of("message", "Not Found"));
				return;
			}
			Map<String, Object> asset = map("id", this.ids.incrementAndGet(), "name", name, "size", raw.length, "state",
					"uploaded");
			release.assets.add(asset);
			send(exchange, 201, asset);
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

	private void repositoryRoute(HttpExchange exchange, Seen request, Repo repo, String rest, byte[] raw) throws Exception {
		String method = request.method();
		JsonNode body = request.body();
		Matcher matcher;
		if (method.equals("GET") && (matcher = match("git/ref/heads/(.+)", rest)) != null) {
			if (refuse(exchange, request, repo, "contents", "read")) {
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
			send(exchange, 200, Map.of("enabled", repo.fullName.equalsIgnoreCase(RUNNER)));
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
		else if (method.equals("GET") && (matcher = match("releases/tags/([^/]+)", rest)) != null) {
			if (refuse(exchange, request, repo, "contents", "read")) {
				return;
			}
			Release release = repo.releases.get(matcher.group(1));
			send(exchange, release == null ? 404 : 200, release == null ? Map.of("message", "Not Found") : release(repo, release));
		}
		else if (method.equals("POST") && rest.equals("releases")) {
			if (refuse(exchange, request, repo, "contents", "write")) {
				return;
			}
			Release release = new Release(this.ids.incrementAndGet());
			repo.releases.put(body.path("tag_name").asString(), release);
			send(exchange, 201, release(repo, release));
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
			String merge = commit();
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

	private void contents(HttpExchange exchange, Seen request, Repo repo, String file) throws IOException {
		boolean workflow = file.startsWith(".github/workflows/");
		if (request.method().equals("GET")) {
			if (refuse(exchange, request, repo, "contents", "read")) {
				return;
			}
			String content = repo.files.get(file);
			if (content == null) {
				// A directory lists its files, as GitHub answers with an array.
				List<Map<String, Object>> listed = new ArrayList<>();
				synchronized (repo.files) {
					repo.files.keySet().stream().filter(path -> path.startsWith(file + "/"))
						.forEach(path -> listed.add(map("name", path.substring(file.length() + 1), "path", path, "type", "file")));
				}
				send(exchange, listed.isEmpty() ? 404 : 200, listed.isEmpty() ? Map.of("message", "Not Found") : listed);
				return;
			}
			send(exchange, 200, Map.of("path", file, "sha", sha(content), "encoding", "base64", "content",
					Base64.getMimeEncoder().encodeToString(content.getBytes(StandardCharsets.UTF_8))));
			return;
		}
		if (refuse(exchange, request, repo, "contents", "write") || (workflow && refuse(exchange, request, repo, "workflows", "write"))) {
			return;
		}
		String existing = repo.files.get(file);
		if (existing != null && !sha(existing).equals(request.body().path("sha").asString(""))) {
			send(exchange, 422, Map.of("message", "Invalid request.\n\n\"sha\" wasn't supplied."));
			return;
		}
		String branch = request.body().path("branch").asString(repo.defaultBranch);
		List<String> list = repo.branches.computeIfAbsent(branch, name -> new ArrayList<>());
		String parent = list.isEmpty() ? null : list.getLast();
		String commit = commit();
		list.add(commit);
		String content = new String(Base64.getMimeDecoder().decode(request.body().path("content").asString("")), StandardCharsets.UTF_8);
		repo.files.put(file, content);
		send(exchange, existing == null ? 201 : 200, map("content", Map.of("path", file, "sha", sha(content)), "commit",
				map("sha", commit, "parents", parent == null ? List.of() : List.of(Map.of("sha", parent)))));
	}

	private void actions(HttpExchange exchange, Seen request, Repo repo, String rest) throws Exception {
		String method = request.method();
		Matcher matcher;
		if (method.equals("POST") && (matcher = match("workflows/([^/]+)/dispatches", rest)) != null) {
			if (refuse(exchange, request, repo, "actions", "write")) {
				return;
			}
			String workflow = matcher.group(1);
			String file = repo.files.get(".github/workflows/" + workflow);
			if (file == null || this.dispatchNotFound > 0) {
				this.dispatchNotFound = Math.max(0, this.dispatchNotFound - 1);
				send(exchange, 404, Map.of("message", "Not Found"));
				return;
			}
			if (!SpikeS6Command.DISPATCH_REF.equals(request.body().path("ref").asString(""))) {
				send(exchange, 422, Map.of("message", "No ref found"));
				return;
			}
			if (this.refuseReturnRunDetails && request.body().has("return_run_details")) {
				send(exchange, 422, Map.of("message", "Invalid request.\n\n\"return_run_details\" is not a permitted key."));
				return;
			}
			JsonNode inputs = request.body().path("inputs");
			String refused = unexpectedInputs(file, inputs);
			if (refused != null) {
				send(exchange, 422, Map.of("message", refused));
				return;
			}
			this.dispatches.add(request.body());
			Run run = new Run(this.ids.incrementAndGet(), workflow, inputs.path("correlation").asString(""));
			this.runs.put(run.id, run);
			ObjectNode result = work(run, inputs);
			JsonNode uploaded = workflow.equals(SpikeS6Command.SCAN_WORKFLOW) ? seal(result, inputs.path("result_key").asString(""))
					: result;
			this.uploadedResults.add(uploaded.toString());
			long artifactId = this.ids.incrementAndGet();
			this.artifacts.put(artifactId, new Artifact(artifactId, run.id, "result-" + inputs.path("correlation").asString(""),
					zip(uploaded)));
			if (this.dispatchStatus == 200 && request.body().path("return_run_details").asBoolean(false)) {
				send(exchange, 200, Map.of("workflow_run_id", run.id, "run_url", apiUrl() + "/repos/" + repo.fullName
						+ "/actions/runs/" + run.id, "html_url", "https://github.com/" + repo.fullName + "/actions/runs/" + run.id));
			}
			else {
				send(exchange, 204, null);
			}
		}
		else if (method.equals("GET") && (matcher = match("workflows/([^/]+)/runs", rest)) != null) {
			if (refuse(exchange, request, repo, "actions", "read")) {
				return;
			}
			String workflow = matcher.group(1);
			List<Map<String, Object>> listed = new ArrayList<>();
			synchronized (this.runs) {
				for (Run run : this.runs.values()) {
					if (run.workflow.equals(workflow)) {
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
			Run run = this.runs.get(Long.parseLong(matcher.group(1)));
			if (run == null) {
				send(exchange, 404, Map.of("message", "Not Found"));
				return;
			}
			String status = status(run, true);
			send(exchange, 200, map("id", run.id, "display_title", run.title, "status", status, "conclusion",
					status.equals("completed") ? run.conclusion : null, "html_url",
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
			Run run = this.runs.get(Long.parseLong(matcher.group(1)));
			if (run == null) {
				send(exchange, 404, Map.of("message", "Not Found"));
				return;
			}
			boolean copy = run.workflow.equals(SpikeS6Command.COPY_WORKFLOW);
			boolean failed = "failure".equals(run.conclusion);
			List<String> names = copy
					? List.of("Set up job", "Check the inputs", "Unseal the tokens", "Copy the commit", "Write the result",
							"Upload the result")
					: List.of("Set up job", "Check the inputs", "Unseal the token", "Fetch the branch into a mirror",
							"Download the scanner", "Set up Java", "Scan the mirror", "Write and seal the result",
							"Upload the result");
			int failing = copy ? 4 : 7;
			List<Map<String, Object>> steps = new ArrayList<>();
			for (int i = 0; i < names.size(); i++) {
				steps.add(map("number", i + 1, "name", names.get(i), "conclusion",
						failed && i + 1 == failing ? "failure" : failed && i + 1 > failing && i + 1 < names.size() - 1 ? "skipped"
								: "success"));
			}
			// The job takes 65 seconds: Free bills it as two minutes.
			send(exchange, 200, map("total_count", 1, "jobs", List.of(map("id", run.id + 1, "name", copy ? "copy" : "scan",
					"started_at", "2026-11-09T03:00:00Z", "completed_at", "2026-11-09T03:01:05Z", "conclusion", run.conclusion,
					"steps", steps))));
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
		else if (method.equals("GET") && (matcher = match("runs/(\\d+)/artifacts", rest)) != null) {
			if (refuse(exchange, request, repo, "actions", "read")) {
				return;
			}
			long runId = Long.parseLong(matcher.group(1));
			List<Map<String, Object>> listed = new ArrayList<>();
			synchronized (this.artifacts) {
				for (Artifact artifact : this.artifacts.values()) {
					if (artifact != null && artifact.runId() == runId && this.runs.get(runId).completed) {
						listed.add(map("id", artifact.id(), "name", artifact.name(), "size_in_bytes", artifact.zip().length,
								"archive_download_url", apiUrl() + "/repos/" + repo.fullName + "/actions/artifacts/" + artifact.id() + "/zip",
								"expired", false));
					}
				}
			}
			send(exchange, 200, map("total_count", listed.size(), "artifacts", listed));
		}
		else if (method.equals("GET") && (matcher = match("runs/(\\d+)/timing", rest)) != null) {
			if (refuse(exchange, request, repo, "actions", "read")) {
				return;
			}
			long duration = 10_000 + Long.parseLong(matcher.group(1)) % 1000;
			Run run = this.runs.get(Long.parseLong(matcher.group(1)));
			// A scan's job takes just over a minute, so Free bills it as two.
			long billed = run != null && run.workflow.equals(SpikeS6Command.SCAN_WORKFLOW) ? 61_000 : duration;
			Map<String, Object> timing = new LinkedHashMap<>();
			timing.put("billable", this.timingWithoutBillable ? Map.of() : Map.of("UBUNTU", map("total_ms", billed, "jobs", 1,
					"job_runs", List.of(Map.of("job_id", 1, "duration_ms", billed)))));
			if (!this.timingWithoutDuration) {
				timing.put("run_duration_ms", duration);
			}
			send(exchange, 200, timing);
		}
		else if (method.equals("DELETE") && (matcher = match("runs/(\\d+)/logs", rest)) != null) {
			if (refuse(exchange, request, repo, "actions", "write")) {
				return;
			}
			this.deletedLogs.add(Long.parseLong(matcher.group(1)));
			send(exchange, 204, null);
		}
		else if (method.equals("GET") && (matcher = match("artifacts/(\\d+)/zip", rest)) != null) {
			if (refuse(exchange, request, repo, "actions", "read")) {
				return;
			}
			long id = Long.parseLong(matcher.group(1));
			if (this.artifacts.get(id) == null) {
				send(exchange, 404, Map.of("message", "Not Found"));
				return;
			}
			exchange.getResponseHeaders().add("Location", apiUrl() + "/blob/" + id + "?sv=2026-11-09&sig=stub" + "signature");
			exchange.sendResponseHeaders(302, -1);
			exchange.close();
		}
		else if (method.equals("DELETE") && (matcher = match("artifacts/(\\d+)", rest)) != null) {
			if (refuse(exchange, request, repo, "actions", "write")) {
				return;
			}
			long id = Long.parseLong(matcher.group(1));
			if (this.artifactDeleteStatus != 204) {
				send(exchange, this.artifactDeleteStatus, Map.of("message", "Forbidden"));
				return;
			}
			this.artifacts.remove(id);
			this.deletedArtifacts.add(id);
			send(exchange, 204, null);
		}
		else {
			send(exchange, 404, Map.of("message", "Not Found", "path", rest));
		}
	}

	/** What the workflow would do with its inputs: unseal, then copy or scan only what the tokens reach. */
	private ObjectNode work(Run run, JsonNode inputs) throws GeneralSecurityException {
		ObjectNode result = GitHubHttp.JSON.createObjectNode();
		result.put("correlation", inputs.path("correlation").asString(""));
		if (run.workflow.equals(SpikeS6Command.COPY_WORKFLOW)) {
			String sourceToken = unseal(inputs.path("source_token").asString(""));
			String targetToken = unseal(inputs.path("target_token").asString(""));
			run.unsealed.add(sourceToken);
			run.unsealed.add(targetToken);
			Repo source = repository(inputs.path("source_repository").asString(""));
			Repo target = repository(inputs.path("target_repository").asString(""));
			List<String> branch = source == null ? null : source.branches.get(inputs.path("source_branch").asString(""));
			String sha = inputs.path("sha").asString("");
			String step;
			if (branch == null || !allows(sourceToken, source, "contents", "read") || "fetch".equals(this.failAtStep)) {
				step = "fetch";
			}
			else if (!branch.contains(sha) || "check".equals(this.failAtStep)) {
				step = "check";
			}
			else if (target == null || !allows(targetToken, target, "contents", "write") || "push".equals(this.failAtStep)) {
				step = "push";
			}
			else {
				String targetBranch = inputs.path("target_branch").asString("");
				if (target.branches.isEmpty()) {
					target.defaultBranch = targetBranch;
				}
				target.branches.put(targetBranch, new ArrayList<>(branch.subList(0, branch.indexOf(sha) + 1)));
				return finish(run, result, "COPIED", "push", null);
			}
			return finish(run, result, "FAILED", step, null);
		}
		String token = unseal(inputs.path("token").asString(""));
		run.unsealed.add(token);
		Repo repo = repository(inputs.path("repository").asString(""));
		List<String> branch = repo == null ? null : repo.branches.get(inputs.path("branch").asString(""));
		if (branch == null || !allows(token, repo, "contents", "read") || !branch.contains(inputs.path("sha").asString(""))) {
			return finish(run, result, "FAILED", "fetch", null);
		}
		ObjectNode output = GitHubHttp.JSON.createObjectNode();
		output.put("commits", branch.size());
		output.put("scannedBlobs", 7);
		output.put("unscannedCount", 0);
		output.putArray("unscanned");
		var found = output.putArray("found");
		found.addObject().put("category", "CREDENTIAL").put("ruleId", "aws-access-key").put("type", "AWS")
			.put("path", "config/secret-path.env").put("commit", branch.getFirst()).put("masked", "AKIA************")
			.put("hash", "h1");
		found.addObject().put("category", "PERSONAL_DATA").put("ruleId", "vn-phone").put("type", "PHONE")
			.put("path", "data/customer-list.csv").put("commit", branch.getFirst()).put("masked", "09********")
			.put("hash", "h2");
		output.put("defaultBranch", inputs.path("branch").asString(""));
		output.put("defaultBranchSha", branch.getLast());
		return finish(run, result, "SCANNED", "scan", output);
	}

	/** The scan's result as s6-scan uploads it: sealed under the dispatch's {@code result_key}, nothing else. */
	private JsonNode seal(JsonNode result, String resultKey) throws GeneralSecurityException {
		PublicKey key = KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(resultKey)));
		Map<String, String> envelope = new LinkedHashMap<>(
				RunnerSealTest.envelope(key, GitHubHttp.JSON.writeValueAsBytes(result)));
		if (this.corruptScanTag) {
			byte[] tag = Base64.getDecoder().decode(envelope.get("tag"));
			tag[0] ^= 1;
			envelope.put("tag", Base64.getEncoder().encodeToString(tag));
		}
		ObjectNode sealed = GitHubHttp.JSON.valueToTree(envelope);
		if (this.plaintextBesideEnvelope) {
			sealed.set("output", result.path("output"));
		}
		return sealed;
	}

	/**
	 * Why GitHub would refuse these inputs for the workflow file the probe wrote: a key it does not declare,
	 * or a required one missing; {@code null} when they fit.
	 */
	@SuppressWarnings("unchecked")
	private static @Nullable String unexpectedInputs(String workflowFile, JsonNode inputs) {
		Map<Object, Object> workflow = (Map<Object, Object>) new Yaml(new SafeConstructor(new LoaderOptions())).load(workflowFile);
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
		}
		return null;
	}

	/**
	 * The run's logs as GitHub zips them: the whole job's log at the top, and one file per step under the
	 * job's folder. The failed copy step's log has {@value #LOG_LINES} lines, a masked token among them.
	 */
	private static byte[] logs(Run run) throws IOException {
		boolean copy = run.workflow.equals(SpikeS6Command.COPY_WORKFLOW);
		String job = copy ? "copy" : "scan";
		StringBuilder step = new StringBuilder();
		for (int line = 1; line <= LOG_LINES; line++) {
			step.append("2026-11-09T03:00:").append(String.format(Locale.ROOT, "%02d", line % 60)).append(".0000000Z ")
				.append(line == LOG_LINES - 1 ? "fatal: could not push with token ***" : line == LOG_LINES
						? "error: failed to push some refs (log line " + line + ")" : "log line " + line)
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
			zip.putNextEntry(new ZipEntry(job + "/" + (copy ? "4_Copy the commit.txt" : "7_Scan the mirror.txt")));
			zip.write(step.toString().getBytes(StandardCharsets.UTF_8));
			zip.closeEntry();
		}
		return bytes.toByteArray();
	}

	private static ObjectNode finish(Run run, ObjectNode result, String outcome, String step, @Nullable JsonNode output) {
		result.put("outcome", outcome);
		result.put("step", step);
		if (output != null) {
			result.set("output", output);
		}
		run.conclusion = outcome.equals("FAILED") ? "failure" : "success";
		return result;
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

	private String unseal(String sealed) throws GeneralSecurityException {
		Cipher cipher = Cipher.getInstance(RunnerSeal.TRANSFORMATION);
		cipher.init(Cipher.DECRYPT_MODE, this.runnerKey, RunnerSeal.OAEP);
		return new String(cipher.doFinal(Base64.getDecoder().decode(sealed)), StandardCharsets.UTF_8);
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
		return installation == PLATFORM_INSTALLATION ? PLATFORM_APP : installation == SOURCE_INSTALLATION ? SOURCE_APP
				: installation == RUNNER_INSTALLATION ? RUNNER_APP : 0;
	}

	private static long installationOf(long app, Repo repo) {
		for (long installation : List.of(PLATFORM_INSTALLATION, SOURCE_INSTALLATION, RUNNER_INSTALLATION)) {
			if (appOf(installation) == app && installationCovers(installation, repo)) {
				return installation;
			}
		}
		return 0;
	}

	private static boolean installationCovers(long installation, Repo repo) {
		return installation == PLATFORM_INSTALLATION ? repo.fullName.startsWith(ORGANIZATION + "/")
				: installation == SOURCE_INSTALLATION ? repo.fullName.equalsIgnoreCase(SOURCE)
						: installation == RUNNER_INSTALLATION && repo.fullName.equalsIgnoreCase(RUNNER);
	}

	/** The installation's permissions, as the App's registration grants them. */
	private static Map<String, String> ceiling(long installation) {
		return installation == SOURCE_INSTALLATION ? Map.of("contents", "read", "metadata", "read")
				: installation == RUNNER_INSTALLATION ? Map.of("actions", "write", "contents", "write", "workflows", "write",
						"metadata", "read")
						: AppExpectations.PLATFORM_PERMISSIONS;
	}

	private static Map<String, Object> registration(long app) {
		if (app == SOURCE_APP) {
			return map("slug", "vibefix-dev-source", "permissions", Map.of("contents", "read", "metadata", "read"), "events",
					List.of());
		}
		if (app == RUNNER_APP) {
			return map("slug", "vibefix-runner-dev", "permissions", ceiling(RUNNER_INSTALLATION), "events", List.of());
		}
		return map("slug", "vibefix-dev-platform", "permissions", AppExpectations.PLATFORM_PERMISSIONS, "events", List.of("push"));
	}

	private Map<String, Object> describe(Repo repo) {
		return map("id", repo.id, "name", repo.fullName.split("/")[1], "full_name", repo.fullName, "private",
				repo.privateRepository, "visibility", repo.privateRepository ? "private" : "public", "default_branch",
				repo.defaultBranch, "has_wiki", false, "has_issues", false, "has_projects", false, "has_discussions", false,
				// The organization forbids forking private repositories, so GitHub reports none allowed.
				"allow_forking", !repo.privateRepository);
	}

	private Map<String, Object> release(Repo repo, Release release) {
		String upload = this.uploadUrl;
		return map("id", release.id, "tag_name", SpikeS6Command.RELEASE, "upload_url", upload != null ? upload
				: apiUrl() + "/uploads/repos/" + repo.fullName + "/releases/" + release.id + "/assets{?name,label}", "assets",
				List.copyOf(release.assets));
	}

	private static String sha(String content) {
		try {
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1").digest(content.getBytes(StandardCharsets.UTF_8)));
		}
		catch (GeneralSecurityException ex) {
			throw new IllegalStateException(ex);
		}
	}

	private static byte[] zip(JsonNode result) throws IOException {
		ByteArrayOutputStream bytes = new ByteArrayOutputStream();
		try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
			zip.putNextEntry(new ZipEntry("result.json"));
			zip.write(GitHubHttp.JSON.writeValueAsBytes(result));
			zip.closeEntry();
		}
		return bytes.toByteArray();
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
