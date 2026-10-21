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
		this.apps.withToken(App.PLATFORM, List.of(target.id()), Map.of("contents", "write", "workflows", "write"), token -> {
			ObjectNode input = JsonNodeFactory.instance.objectNode();
			input.put("source", sourceFullName);
			input.put("sha", sha);
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
