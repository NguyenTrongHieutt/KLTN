package vn.vibefix.github.internal;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;
import vn.vibefix.github.GitHubApps.App;
import vn.vibefix.github.GitHubApps.Unnarrowed;
import vn.vibefix.github.GitHubException;
import vn.vibefix.github.PlatformRepositories;
import vn.vibefix.shared.SandboxJobException;
import vn.vibefix.shared.SandboxJobs;

/**
 * {@link PlatformRepositories} with the Platform App (AD-10, AD-11, Spike S4). Creating a repository and
 * forking use AD-10's unnarrowed purposes, since GitHub refuses both to a token narrowed by
 * {@code repository_ids}; every other call is narrowed to its repository and the permissions it needs.
 * The rulesets are defined once here, for Setup and for the sweep (AD-12).
 */
@Component
class GitHubPlatformRepositories implements PlatformRepositories {

	/** AD-11's ruleset on the default branch. */
	static final String DEFAULT_BRANCH_RULESET = "vibefix-default-branch";

	/** AD-11's ruleset on {@code handover/**}. */
	static final String HANDOVER_RULESET = "vibefix-handover";

	static final Map<String, Set<String>> RULES = Map.of(DEFAULT_BRANCH_RULESET, Set.of("update", "non_fast_forward", "deletion"),
			HANDOVER_RULESET, Set.of("creation", "update", "deletion"));

	static final Map<String, String> REFS = Map.of(DEFAULT_BRANCH_RULESET, "~DEFAULT_BRANCH", HANDOVER_RULESET,
			"refs/heads/handover/**");

	/** GitHub expires a repository invitation 7 days after it is sent. */
	static final Duration INVITATION_LIFETIME = Duration.ofDays(7);

	private static final Map<String, Object> FEATURES_OFF = Map.of("has_wiki", false, "has_issues", false, "has_projects",
			false, "has_discussions", false);

	private final GitHubAppsClient apps;

	private final SandboxJobs sandbox;

	private final Duration forkPoll;

	private final Duration forkWait;

	private final Clock clock;

	private volatile @Nullable String bot;

	GitHubPlatformRepositories(GitHubAppsClient apps, SandboxJobs sandbox,
			@Value("${vibefix.github.fork-poll:2s}") Duration forkPoll, @Value("${vibefix.github.fork-wait:5m}") Duration forkWait,
			Clock clock) {
		this.apps = apps;
		this.sandbox = sandbox;
		this.forkPoll = forkPoll;
		this.forkWait = forkWait;
		this.clock = clock;
	}

	@Override
	public Optional<PlatformRepository> find(String name) {
		return this.apps.withUnnarrowed(App.PLATFORM, Unnarrowed.ORGANIZATION_READ, token -> {
			GitHubHttp.Answer answer = http().send("GET", "/repos/" + organization() + "/" + name, token.value(), null);
			if (answer.status() == 404) {
				return Optional.empty();
			}
			return Optional.of(repository(require(answer, "GET the repository " + name), name));
		});
	}

	@Override
	public PlatformRepository create(String name) {
		return this.apps.withUnnarrowed(App.PLATFORM, Unnarrowed.REPOSITORY_CREATION, token -> {
			Map<String, Object> body = new java.util.LinkedHashMap<>(FEATURES_OFF);
			body.put("name", name);
			body.put("private", false);
			body.put("auto_init", false);
			GitHubHttp.Answer answer = http().send("POST", "/orgs/" + organization() + "/repos", token.value(), body);
			return repository(require(answer, "create the repository " + name), name);
		});
	}

	@Override
	public void copy(String sourceFullName, String sha, String branch, PlatformRepository target) {
		copy(sourceFullName, sha, branch, branch, target);
	}

	@Override
	public void copy(String sourceFullName, String sha, String sourceBranch, String branch, PlatformRepository target) {
		this.apps.withToken(App.PLATFORM, List.of(target.id()), Map.of("contents", "write", "workflows", "write"), token -> {
			ObjectNode input = JsonNodeFactory.instance.objectNode();
			input.put("source", sourceFullName);
			input.put("sha", sha);
			input.put("sourceBranch", sourceBranch);
			input.put("branch", branch);
			input.put("target", target.fullName());
			input.put("token", token.value());
			JsonNode output;
			try {
				output = this.sandbox.run(RepositoryCopyJobHandler.TYPE, input).output();
			}
			catch (SandboxJobException ex) {
				throw new GitHubException(0, "The copy into " + target.fullName() + " did not run: " + ex.getMessage());
			}
			if (!sha.equals(output.path("pushed").asString(""))) {
				throw new GitHubException(0, "The copy into " + target.fullName() + " was refused: "
						+ output.path("refused").asString("UNKNOWN"));
			}
			return null;
		});
	}

	@Override
	public PlatformRepository fork(PlatformRepository from, String name) {
		GitHubHttp.Answer answer = this.apps.withUnnarrowed(App.PLATFORM, Unnarrowed.FORK,
				token -> http().send("POST", "/repos/" + from.fullName() + "/forks", token.value(),
						Map.of("organization", organization(), "name", name, "default_branch_only", true)));
		if (answer.status() != 202 && answer.status() != 201) {
			throw new GitHubException(answer.status(), "GitHub refused the fork of " + from.fullName() + ": HTTP "
					+ answer.status());
		}
		PlatformRepository fork = repository(answer.body(), name);
		long waited = 0;
		while (true) {
			GitHubHttp.Answer ready = this.apps.withToken(App.PLATFORM, List.of(fork.id()), Map.of("contents", "read"),
					token -> http().send("GET", "/repos/" + fork.fullName() + "/commits?per_page=1", token.value(), null));
			if (ready.ok() && ready.body().size() > 0) {
				break;
			}
			if (waited >= this.forkWait.toMillis()) {
				throw new GitHubException(ready.status(), "The fork " + fork.fullName() + " was not ready after "
						+ this.forkWait.toSeconds() + " s");
			}
			sleep();
			waited += this.forkPoll.toMillis();
		}
		return fork;
	}

	@Override
	public void turnFeaturesOff(PlatformRepository repository) {
		this.apps.withToken(App.PLATFORM, List.of(repository.id()), Map.of("administration", "write"), token -> {
			require(http().send("PATCH", "/repos/" + repository.fullName(), token.value(), FEATURES_OFF),
					"turn the features of " + repository.fullName() + " off");
			return null;
		});
	}

	@Override
	public List<String> organizationDifferences() {
		return this.apps.withUnnarrowed(App.PLATFORM, Unnarrowed.ORGANIZATION_READ, token -> {
			JsonNode org = require(http().send("GET", "/orgs/" + organization(), token.value(), null), "read the organization");
			JsonNode actions = require(http().send("GET", "/orgs/" + organization() + "/actions/permissions", token.value(), null),
					"read the organization's Actions permissions");
			List<String> differences = new ArrayList<>();
			if (!org.path("two_factor_requirement_enabled").asBoolean(false)) {
				differences.add("two-factor authentication is not required");
			}
			if (!"none".equals(org.path("default_repository_permission").asString(""))) {
				differences.add("base permission is " + org.path("default_repository_permission").asString("absent")
						+ " instead of none");
			}
			if (!"none".equals(actions.path("enabled_repositories").asString(""))) {
				differences.add("GitHub Actions are enabled for " + actions.path("enabled_repositories").asString("absent")
						+ " repositories");
			}
			return differences;
		});
	}

	@Override
	public void createRulesets(PlatformRepository intermediary) {
		long appId = this.apps.appId(App.PLATFORM);
		List<Map<String, Object>> bypass = List.of(Map.of("actor_id", appId, "actor_type", "Integration", "bypass_mode", "always"));
		this.apps.withToken(App.PLATFORM, List.of(intermediary.id()), Map.of("administration", "write"), token -> {
			String path = "/repos/" + intermediary.fullName() + "/rulesets";
			Set<String> existing = new HashSet<>();
			for (JsonNode ruleset : require(http().send("GET", path, token.value(), null), "list the rulesets")) {
				existing.add(ruleset.path("name").asString(""));
			}
			for (String name : List.of(DEFAULT_BRANCH_RULESET, HANDOVER_RULESET)) {
				if (existing.contains(name)) {
					continue;
				}
				List<Map<String, String>> rules = RULES.get(name).stream().sorted().map(type -> Map.of("type", type)).toList();
				require(http().send("POST", path, token.value(), Map.of("name", name, "target", "branch", "enforcement", "active",
						"conditions", Map.of("ref_name", Map.of("include", List.of(REFS.get(name)), "exclude", List.of())),
						"rules", rules, "bypass_actors", bypass)), "create the ruleset " + name);
			}
			return null;
		});
	}

	@Override
	public List<String> rulesetDifferences(PlatformRepository intermediary) {
		long appId = this.apps.appId(App.PLATFORM);
		// GitHub shows bypass_actors only to a caller with write access to the ruleset (Spike S4).
		return this.apps.withToken(App.PLATFORM, List.of(intermediary.id()), Map.of("administration", "write"), token -> {
			String path = "/repos/" + intermediary.fullName() + "/rulesets";
			Map<String, JsonNode> found = new java.util.HashMap<>();
			for (JsonNode summary : require(http().send("GET", path, token.value(), null), "list the rulesets")) {
				String name = summary.path("name").asString("");
				if (RULES.containsKey(name)) {
					found.put(name, require(http().send("GET", path + "/" + summary.path("id").asString(), token.value(), null),
							"read the ruleset " + name));
				}
			}
			List<String> differences = new ArrayList<>();
			for (String name : List.of(DEFAULT_BRANCH_RULESET, HANDOVER_RULESET)) {
				JsonNode ruleset = found.get(name);
				if (ruleset == null) {
					differences.add(name + ": missing");
					continue;
				}
				if (!"active".equals(ruleset.path("enforcement").asString(""))) {
					differences.add(name + ": not active");
				}
				Set<String> rules = new HashSet<>();
				ruleset.path("rules").forEach(rule -> rules.add(rule.path("type").asString("")));
				if (!rules.equals(RULES.get(name))) {
					differences.add(name + ": rules " + new java.util.TreeSet<>(rules) + " instead of "
							+ new java.util.TreeSet<>(RULES.get(name)));
				}
				Set<String> includes = new HashSet<>();
				ruleset.path("conditions").path("ref_name").path("include").forEach(ref -> includes.add(ref.asString("")));
				if (!includes.equals(Set.of(REFS.get(name)))) {
					differences.add(name + ": applies to " + includes + " instead of " + REFS.get(name));
				}
				JsonNode actors = ruleset.path("bypass_actors");
				boolean onlyTheApp = actors.size() == 1 && "Integration".equals(actors.get(0).path("actor_type").asString(""))
						&& actors.get(0).path("actor_id").asLong() == appId
						&& "always".equals(actors.get(0).path("bypass_mode").asString(""));
				if (!onlyTheApp) {
					differences.add(name + ": the Platform App is not the only bypass actor");
				}
			}
			return differences;
		});
	}

	@Override
	public Invited invite(PlatformRepository repository, String login) {
		return this.apps.withToken(App.PLATFORM, List.of(repository.id()), Map.of("administration", "write"), token -> {
			GitHubHttp.Answer answer = http().send("PUT", "/repos/" + repository.fullName() + "/collaborators/" + login,
					token.value(), Map.of("permission", "pull"));
			if (answer.status() == 204) {
				return new Invited(null, null);
			}
			JsonNode body = require(answer, "invite " + login);
			Instant created = instant(body.path("created_at").asString(""));
			return new Invited(body.path("id").asLong(),
					(created == null ? this.clock.instant() : created).plus(INVITATION_LIFETIME));
		});
	}

	@Override
	public boolean collaborator(PlatformRepository repository, String login) {
		return this.apps.withToken(App.PLATFORM, List.of(repository.id()), Map.of("administration", "write"), token -> {
			GitHubHttp.Answer answer = http().send("GET", "/repos/" + repository.fullName() + "/collaborators/" + login,
					token.value(), null);
			if (answer.status() == 404) {
				return false;
			}
			require(answer, "read whether " + login + " is a collaborator");
			return true;
		});
	}

	@Override
	public void raise(PlatformRepository repository, String login) {
		this.apps.withToken(App.PLATFORM, List.of(repository.id()), Map.of("administration", "write"), token -> {
			GitHubHttp.Answer answer = http().send("PUT", "/repos/" + repository.fullName() + "/collaborators/" + login,
					token.value(), Map.of("permission", "push"));
			if (answer.status() != 204) {
				throw new GitHubException(answer.status(), "Raising " + login + " did not change a collaborator in place: HTTP "
						+ answer.status());
			}
			return null;
		});
	}

	@Override
	public String role(PlatformRepository repository, String login) {
		return this.apps.withToken(App.PLATFORM, List.of(repository.id()), Map.of("metadata", "read"), token -> {
			GitHubHttp.Answer answer = http().send("GET",
					"/repos/" + repository.fullName() + "/collaborators/" + login + "/permission", token.value(), null);
			if (answer.status() == 404) {
				return "none";
			}
			JsonNode body = require(answer, "read the permission of " + login);
			return body.path("role_name").asString(body.path("permission").asString("none"));
		});
	}

	@Override
	public Set<String> withoutTwoFactor() {
		return this.apps.withUnnarrowed(App.PLATFORM, Unnarrowed.ORGANIZATION_READ, token -> {
			Set<String> logins = new HashSet<>();
			JsonNode list = require(http().send("GET",
					"/orgs/" + organization() + "/outside_collaborators?filter=2fa_disabled&per_page=100", token.value(), null),
					"list collaborators without two-factor authentication");
			list.forEach(user -> logins.add(user.path("login").asString("").toLowerCase(Locale.ROOT)));
			return logins;
		});
	}

	@Override
	public boolean organizationMember(String login) {
		return this.apps.withUnnarrowed(App.PLATFORM, Unnarrowed.ORGANIZATION_READ, token -> {
			GitHubHttp.Answer answer = http().send("GET", "/orgs/" + organization() + "/members/" + login, token.value(), null);
			if (answer.status() == 404) {
				return false;
			}
			require(answer, "read whether " + login + " is an organization member");
			return true;
		});
	}

	@Override
	public void removeCollaborator(PlatformRepository repository, String login) {
		this.apps.withToken(App.PLATFORM, List.of(repository.id()), Map.of("administration", "write"), token -> {
			GitHubHttp.Answer answer = http().send("DELETE", "/repos/" + repository.fullName() + "/collaborators/" + login,
					token.value(), null);
			if (answer.status() != 404) {
				require(answer, "remove " + login);
			}
			return null;
		});
	}

	@Override
	public Map<String, String> refs(PlatformRepository repository) {
		return this.apps.withToken(App.PLATFORM, List.of(repository.id()), Map.of("contents", "read"), token -> {
			Map<String, String> refs = new java.util.TreeMap<>();
			for (String kind : List.of("heads", "tags")) {
				GitHubHttp.Answer answer = http().send("GET", "/repos/" + repository.fullName() + "/git/matching-refs/" + kind
						+ "?per_page=100", token.value(), null);
				if (answer.status() == 404 || answer.status() == 409) {
					// An empty repository has no refs.
					continue;
				}
				for (JsonNode ref : require(answer, "list the " + kind + " of " + repository.fullName())) {
					refs.put(ref.path("ref").asString(""), ref.path("object").path("sha").asString(""));
				}
			}
			return refs;
		});
	}

	@Override
	public String botLogin() {
		String cached = this.bot;
		if (cached == null) {
			cached = this.apps.registration(App.PLATFORM).slug().toLowerCase(Locale.ROOT) + "[bot]";
			this.bot = cached;
		}
		return cached;
	}

	@Override
	public List<PullRequest> openPullRequests(PlatformRepository repository) {
		return this.apps.withToken(App.PLATFORM, List.of(repository.id()), Map.of("pull_requests", "read"), token -> {
			List<PullRequest> open = new ArrayList<>();
			for (JsonNode pull : require(http().send("GET", "/repos/" + repository.fullName() + "/pulls?state=open&per_page=100",
					token.value(), null), "list the pull requests of " + repository.fullName())) {
				open.add(new PullRequest(pull.path("number").asInt(),
						pull.path("user").path("login").asString("").toLowerCase(Locale.ROOT)));
			}
			return open;
		});
	}

	@Override
	public void closePullRequest(PlatformRepository repository, int number) {
		this.apps.withToken(App.PLATFORM, List.of(repository.id()), Map.of("pull_requests", "write"), token -> {
			require(http().send("PATCH", "/repos/" + repository.fullName() + "/pulls/" + number, token.value(),
					Map.of("state", "closed")), "close pull request " + number);
			return null;
		});
	}

	@Override
	public int openPullRequest(PlatformRepository repository, String head, String base, String title, String body) {
		return this.apps.withToken(App.PLATFORM, List.of(repository.id()), Map.of("pull_requests", "write"), token -> {
			String path = "/repos/" + repository.fullName() + "/pulls";
			String owner = repository.fullName().substring(0, repository.fullName().indexOf('/'));
			JsonNode existing = require(http().send("GET", path + "?state=all&per_page=1&head="
					+ java.net.URLEncoder.encode(owner + ":" + head, java.nio.charset.StandardCharsets.UTF_8), token.value(), null),
					"find the pull request from " + head);
			if (existing.size() > 0) {
				return existing.get(0).path("number").asInt();
			}
			return require(http().send("POST", path, token.value(), Map.of("title", title, "head", head, "base", base, "body", body,
					"maintainer_can_modify", false)), "open the pull request from " + head).path("number").asInt();
		});
	}

	@Override
	public Comparison compare(PlatformRepository repository, String baseSha, String headSha) {
		return this.apps.withToken(App.PLATFORM, List.of(repository.id()), Map.of("contents", "read"), token -> {
			JsonNode body = require(http().send("GET", "/repos/" + repository.fullName() + "/compare/" + baseSha + "..." + headSha,
					token.value(), null), "compare " + repository.fullName());
			List<ComparedCommit> commits = new ArrayList<>();
			for (JsonNode commit : body.path("commits")) {
				String message = commit.path("commit").path("message").asString("");
				int end = message.indexOf('\n');
				String subject = (end < 0 ? message : message.substring(0, end)).strip();
				JsonNode author = commit.path("author");
				commits.add(new ComparedCommit(commit.path("sha").asString(""),
						subject.length() > 300 ? subject.substring(0, 300) : subject,
						author.path("id").isNumber() ? author.path("id").asLong() : null,
						author.path("login").isString() ? author.path("login").asString() : null));
			}
			java.util.LinkedHashSet<String> files = new java.util.LinkedHashSet<>();
			for (JsonNode file : body.path("files")) {
				if (file.path("previous_filename").isString()) {
					files.add(file.path("previous_filename").asString());
				}
				files.add(file.path("filename").asString(""));
			}
			boolean truncated = body.path("total_commits").asInt(commits.size()) > commits.size() || files.size() >= 300;
			return new Comparison(commits, List.copyOf(files), truncated);
		});
	}

	@Override
	public Map<String, String> directCollaborators(PlatformRepository repository) {
		return this.apps.withToken(App.PLATFORM, List.of(repository.id()), Map.of("administration", "write"), token -> {
			Map<String, String> collaborators = new java.util.TreeMap<>();
			for (JsonNode user : require(http().send("GET",
					"/repos/" + repository.fullName() + "/collaborators?affiliation=direct&per_page=100", token.value(), null),
					"list the collaborators of " + repository.fullName())) {
				collaborators.put(user.path("login").asString("").toLowerCase(Locale.ROOT),
						user.path("role_name").asString("read"));
			}
			return collaborators;
		});
	}

	@Override
	public Set<String> pendingInvitees(PlatformRepository repository) {
		return this.apps.withToken(App.PLATFORM, List.of(repository.id()), Map.of("administration", "write"), token -> {
			Set<String> invitees = new java.util.TreeSet<>();
			for (JsonNode invitation : require(http().send("GET", "/repos/" + repository.fullName() + "/invitations?per_page=100",
					token.value(), null), "list the invitations of " + repository.fullName())) {
				invitees.add(invitation.path("invitee").path("login").asString("").toLowerCase(Locale.ROOT));
			}
			return invitees;
		});
	}

	@Override
	public List<String> featureDifferences(PlatformRepository repository) {
		return this.apps.withToken(App.PLATFORM, List.of(repository.id()), Map.of("metadata", "read"), token -> {
			JsonNode body = require(http().send("GET", "/repos/" + repository.fullName(), token.value(), null),
					"read " + repository.fullName());
			List<String> differences = new ArrayList<>();
			for (String feature : List.of("has_wiki", "has_issues", "has_projects", "has_discussions")) {
				if (body.path(feature).asBoolean(false)) {
					differences.add(repository.fullName() + ": " + feature + " is on");
				}
			}
			return differences;
		});
	}

	@Override
	public List<String> organizationPeopleDifferences(@Nullable String operatorLogin) {
		return this.apps.withUnnarrowed(App.PLATFORM, Unnarrowed.ORGANIZATION_READ, token -> {
			List<String> differences = new ArrayList<>();
			Set<String> owners = logins(require(http().send("GET", "/orgs/" + organization() + "/members?role=admin&per_page=100",
					token.value(), null), "list the organization's owners"));
			Set<String> members = logins(require(http().send("GET", "/orgs/" + organization() + "/members?role=member&per_page=100",
					token.value(), null), "list the organization's members"));
			if (operatorLogin != null && !operatorLogin.isBlank()) {
				Set<String> others = new java.util.TreeSet<>(owners);
				others.remove(operatorLogin.toLowerCase(Locale.ROOT));
				if (!others.isEmpty()) {
					differences.add("organization owners besides the operator account: " + others);
				}
			}
			if (!members.isEmpty()) {
				differences.add("organization members who are not owners: " + new java.util.TreeSet<>(members));
			}
			return differences;
		});
	}

	@Override
	public List<String> installationDifferences() {
		GitHubHttp.Answer answer = this.apps.installation(App.PLATFORM);
		JsonNode body = require(answer, "read the Platform App's installation");
		List<String> differences = new ArrayList<>();
		String selection = body.path("repository_selection").asString("");
		if (!"all".equals(selection)) {
			differences.add("installation repository selection: " + selection + " instead of all");
		}
		if (!body.path("suspended_at").isMissingNode() && !body.path("suspended_at").isNull()) {
			differences.add("installation suspended");
		}
		return differences;
	}

	@Override
	public Set<String> recentActors(PlatformRepository repository) {
		return this.apps.withToken(App.PLATFORM, List.of(repository.id()), Map.of("metadata", "read"), token -> {
			Set<String> actors = new java.util.TreeSet<>();
			GitHubHttp.Answer answer = http().send("GET", "/repos/" + repository.fullName() + "/events?per_page=30",
					token.value(), null);
			for (JsonNode event : require(answer, "list the events of " + repository.fullName())) {
				actors.add(event.path("actor").path("login").asString("").toLowerCase(Locale.ROOT));
			}
			return actors;
		});
	}

	@Override
	public Optional<SourceRepository> source(long githubId) {
		return this.apps.withUnnarrowed(App.PLATFORM, Unnarrowed.ORGANIZATION_READ, token -> {
			GitHubHttp.Answer answer = http().send("GET", "/repositories/" + githubId, token.value(), null);
			if (answer.status() == 404 || answer.status() == 403) {
				return Optional.empty();
			}
			JsonNode body = require(answer, "read repository " + githubId);
			return Optional.of(new SourceRepository(body.path("id").asLong(), body.path("full_name").asString(""),
					body.path("default_branch").asString("main"), body.path("archived").asBoolean(false)));
		});
	}

	@Override
	public List<String> commitsSince(String fullName, String baseSha, String branch) {
		return this.apps.withUnnarrowed(App.PLATFORM, Unnarrowed.ORGANIZATION_READ, token -> {
			GitHubHttp.Answer answer = http().send("GET", "/repos/" + fullName + "/compare/" + baseSha + "..." + branch,
					token.value(), null);
			if (answer.status() == 404) {
				// The copied commit is gone from the branch's history: every commit counts as new.
				return List.of("unknown");
			}
			List<String> commits = new ArrayList<>();
			for (JsonNode commit : require(answer, "compare " + fullName).path("commits")) {
				commits.add(commit.path("sha").asString(""));
			}
			return commits;
		});
	}

	@Override
	public void removeInvitation(PlatformRepository repository, String login) {
		this.apps.withToken(App.PLATFORM, List.of(repository.id()), Map.of("administration", "write"), token -> {
			String path = "/repos/" + repository.fullName() + "/invitations";
			for (JsonNode invitation : require(http().send("GET", path + "?per_page=100", token.value(), null),
					"list the invitations of " + repository.fullName())) {
				if (invitation.path("invitee").path("login").asString("").equalsIgnoreCase(login)) {
					GitHubHttp.Answer removed = http().send("DELETE", path + "/" + invitation.path("id").asLong(), token.value(), null);
					if (removed.status() != 404) {
						require(removed, "remove the invitation of " + login);
					}
				}
			}
			return null;
		});
	}

	@Override
	public byte[] zip(PlatformRepository repository, String branch) {
		ObjectNode input = JsonNodeFactory.instance.objectNode();
		input.put("repository", repository.fullName());
		input.put("branch", branch);
		JsonNode output;
		try {
			output = this.sandbox.run(RepositoryZipJobHandler.TYPE, input).output();
		}
		catch (SandboxJobException ex) {
			throw new GitHubException(0, "The ZIP of " + repository.fullName() + " did not run: " + ex.getMessage());
		}
		if (!output.path("zip").isString()) {
			throw new GitHubException(0, "The ZIP of " + repository.fullName() + " was refused: "
					+ output.path("refused").asString("UNKNOWN"));
		}
		return java.util.Base64.getDecoder().decode(output.path("zip").asString());
	}

	@Override
	public void delete(PlatformRepository repository) {
		this.apps.withToken(App.PLATFORM, List.of(repository.id()), Map.of("administration", "write"), token -> {
			GitHubHttp.Answer answer = http().send("DELETE", "/repos/" + repository.fullName(), token.value(), null);
			if (answer.status() != 404) {
				require(answer, "delete " + repository.fullName());
			}
			return null;
		});
	}

	private static Set<String> logins(JsonNode users) {
		Set<String> logins = new java.util.TreeSet<>();
		users.forEach(user -> logins.add(user.path("login").asString("").toLowerCase(Locale.ROOT)));
		return logins;
	}

	private static @Nullable Instant instant(String text) {
		try {
			return text.isEmpty() ? null : Instant.parse(text);
		}
		catch (DateTimeParseException ex) {
			return null;
		}
	}

	private GitHubHttp http() {
		return this.apps.http();
	}

	private String organization() {
		String organization = this.apps.properties().organization();
		if (organization == null || organization.isBlank()) {
			throw new GitHubException(0, "vibefix.github.organization is not configured");
		}
		return organization;
	}

	private PlatformRepository repository(JsonNode body, String name) {
		String actual = body.path("name").asString(name);
		return new PlatformRepository(body.path("id").asLong(), actual, organization() + "/" + actual);
	}

	private static JsonNode require(GitHubHttp.Answer answer, String what) {
		if (!answer.ok()) {
			throw new GitHubException(answer.status(), "GitHub refused to " + what + ": HTTP " + answer.status() + " "
					+ answer.body().path("message").asString(""));
		}
		return answer.body();
	}

	private void sleep() {
		try {
			Thread.sleep(this.forkPoll.toMillis());
		}
		catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
			throw new GitHubException(0, "Interrupted while waiting for a fork");
		}
	}

}
