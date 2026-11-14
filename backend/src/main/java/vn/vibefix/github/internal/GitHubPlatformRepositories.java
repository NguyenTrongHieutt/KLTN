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
import org.springframework.stereotype.Component;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;
import vn.vibefix.github.GitHubApps.App;
import vn.vibefix.github.GitHubApps.InstallationToken;
import vn.vibefix.github.GitHubApps.Unnarrowed;
import vn.vibefix.github.GitHubException;
import vn.vibefix.github.PlatformRepositories;
import vn.vibefix.shared.SandboxJobException;
import vn.vibefix.shared.SandboxJobs;

/**
 * {@link PlatformRepositories} with the Platform App (AD-10, AD-11, Spike S4, Spike S6). Creating a
 * repository uses AD-10's unnarrowed creation purpose, since the repository does not exist yet; every
 * other call is narrowed to its repository and the permissions it needs. Both Job repositories are private
 * copies (D1, D5): every pull-request call holds {@code contents} read besides its {@code pull_requests}
 * permission, as GitHub needs on a private repository (Spike S6 Part C). Nothing here clones or pushes code: the
 * handover copy runs on the runner's Actions (AD-46, Story 23.8a). The runner's state is defined once here, for
 * Setup's gate and for the sweep (AD-12, AD-46).
 */
@Component
class GitHubPlatformRepositories implements PlatformRepositories {

	/** The runner's one workflow, as {@code deploy/runner/} publishes it (AD-46). */
	static final String RUNNER_WORKFLOW = "s6-copy.yml";

	/** GitHub expires a repository invitation 7 days after it is sent. */
	static final Duration INVITATION_LIFETIME = Duration.ofDays(7);

	/** The features AD-11 keeps off on every platform repository, as GitHub names them. */
	static final List<String> FEATURES = List.of("has_wiki", "has_issues", "has_projects", "has_discussions");

	/**
	 * A pull-request read on a private repository: GitHub needs {@code contents} read besides
	 * {@code pull_requests} (Spike S6 Part C, deferred-work 659).
	 */
	static final Map<String, String> PULLS_READ = Map.of("contents", "read", "pull_requests", "read");

	/** A pull-request write on a private repository, which needs {@code contents} read too (Spike S6 Part C). */
	static final Map<String, String> PULLS_WRITE = Map.of("contents", "read", "pull_requests", "write");

	private final GitHubAppsClient apps;

	private final SandboxJobs sandbox;

	private final Clock clock;

	private volatile @Nullable String bot;

	GitHubPlatformRepositories(GitHubAppsClient apps, SandboxJobs sandbox, Clock clock) {
		this.apps = apps;
		this.sandbox = sandbox;
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
			Map<String, Object> body = new java.util.LinkedHashMap<>();
			body.put("name", name);
			body.put("private", true);
			FEATURES.forEach(feature -> body.put(feature, false));
			// GitHub's create takes no allow_forking: the organization's private forking setting decides it, which
			// organizationDifferences checks and settingsDifferences reads back.
			body.put("auto_init", false);
			GitHubHttp.Answer answer = http().send("POST", "/orgs/" + organization() + "/repos", token.value(), body);
			return repository(require(answer, "create the repository " + name), name);
		});
	}

	@Override
	public Optional<PlatformRepository> runner() {
		String runner = this.apps.properties().runnerRepository();
		return runner == null ? Optional.empty() : find(runner);
	}

	@Override
	public String runnerWorkflow() {
		return RUNNER_WORKFLOW;
	}

	@Override
	public List<String> organizationDifferences() {
		return this.apps.withUnnarrowed(App.PLATFORM, Unnarrowed.ORGANIZATION_READ, token -> {
			JsonNode org = require(http().send("GET", "/orgs/" + organization(), token.value(), null), "read the organization");
			List<String> differences = new ArrayList<>();
			if (!org.path("two_factor_requirement_enabled").asBoolean(false)) {
				differences.add("two-factor authentication is not required");
			}
			if (!"none".equals(org.path("default_repository_permission").asString(""))) {
				differences.add("base permission is " + org.path("default_repository_permission").asString("absent")
						+ " instead of none");
			}
			if (org.path("members_can_fork_private_repositories").asBoolean(false)) {
				// AD-11: a new private repository's allow_forking follows this setting (Spike S6).
				differences.add("members may fork private repositories (members_can_fork_private_repositories=true)"
						+ " instead of never");
			}
			String runner = this.apps.properties().runnerRepository();
			if (runner == null) {
				// AD-46: GitHub Actions run on the runner only, so a missing runner is a difference, never a fallback.
				differences.add("vibefix.github.runner-repository is not set: GitHub Actions must run on the runner only");
			}
			else {
				JsonNode actions = require(http().send("GET", "/orgs/" + organization() + "/actions/permissions", token.value(),
						null), "read the organization's Actions permissions");
				differences.addAll(actionsDifferences(runner, actions, token));
			}
			return differences;
		});
	}

	@Override
	public List<String> runnerDifferences() {
		String runner = this.apps.properties().runnerRepository();
		if (runner == null) {
			return List.of();
		}
		String fullName = organization() + "/" + runner;
		GitHubHttp.Answer answer = this.apps.withUnnarrowed(App.PLATFORM, Unnarrowed.ORGANIZATION_READ,
				token -> http().send("GET", "/repos/" + fullName, token.value(), null));
		if (answer.status() == 404) {
			return List.of("the runner " + fullName + " does not exist");
		}
		JsonNode body = require(answer, "read the runner " + fullName);
		PlatformRepository repository = repository(body, runner);
		List<String> differences = new ArrayList<>();
		if (!body.path("private").asBoolean(false)) {
			differences.add("the runner " + fullName + " is public instead of private");
		}
		String branch = body.path("default_branch").asString("main");
		String ref = java.net.URLEncoder.encode(branch, java.nio.charset.StandardCharsets.UTF_8);
		String workflowPath = ".github/workflows/" + RUNNER_WORKFLOW;
		AcceptedWorkflows.Checked[] blob = { null };
		List<String> workflows = this.apps.withToken(App.PLATFORM, List.of(repository.id()), Map.of("contents", "read"),
				token -> {
					GitHubHttp.Answer listed = http().send("GET", "/repos/" + fullName + "/contents/.github/workflows?ref=" + ref,
							token.value(), null);
					List<String> names = new ArrayList<>();
					if (listed.status() != 404) {
						require(listed, "list the workflows of the runner " + fullName)
							.forEach(entry -> names.add(entry.path("name").asString("")));
					}
					if (names.contains(RUNNER_WORKFLOW)) {
						// AD-45 (deferred runner check): the copy workflow must be an accepted blob. A read GitHub fails
						// throws, as every other failed read of the sweep does.
						GitHubHttp.Answer file = http().send("GET", "/repos/" + fullName + "/contents/" + workflowPath + "?ref="
								+ ref, token.value(), null);
						blob[0] = new AcceptedWorkflows(this.apps.properties(), this.clock).check(workflowPath, file);
					}
					return names.stream().sorted().toList();
				});
		if (!workflows.equals(List.of(RUNNER_WORKFLOW))) {
			differences.add("the runner " + fullName + " holds " + workflows + " in .github/workflows instead of ["
					+ RUNNER_WORKFLOW + "] only");
		}
		if (blob[0] != null && !blob[0].accepted()) {
			differences.add("the runner " + fullName + "'s " + workflowPath + " is "
					+ (blob[0].blob() == null ? "missing" : "blob " + blob[0].blob())
					+ ", which vibefix.github.exchange.accepted-blobs does not accept");
		}
		Map<String, String> collaborators = directCollaborators(repository);
		if (!collaborators.isEmpty()) {
			differences.add("the runner " + fullName + " has collaborators " + collaborators.keySet());
		}
		Set<String> invitees = pendingInvitees(repository);
		if (!invitees.isEmpty()) {
			differences.add("the runner " + fullName + " has invitations for " + invitees);
		}
		return differences;
	}

	/**
	 * Actions run on the runner only (Spike S6 revision, AD-46): "Selected repositories", listing it alone
	 * ({@code GET /orgs/{org}/actions/permissions/repositories}), with the organization's own actions only
	 * ({@code allowed_actions} {@code local_only}). Workflows get read tokens by default and may not approve
	 * pull requests ({@code GET /orgs/{org}/actions/permissions/workflow}).
	 */
	private List<String> actionsDifferences(String runner, JsonNode actions, InstallationToken token) {
		List<String> differences = new ArrayList<>();
		String enabled = actions.path("enabled_repositories").asString("");
		if ("none".equals(enabled)) {
			differences.add("GitHub Actions are enabled for no repository instead of the runner " + runner + " only");
		}
		else if (!"selected".equals(enabled)) {
			differences.add("GitHub Actions are enabled for " + (enabled.isEmpty() ? "absent" : enabled)
					+ " repositories instead of the runner " + runner + " only");
		}
		else {
			JsonNode selected = require(http().send("GET",
					"/orgs/" + organization() + "/actions/permissions/repositories?per_page=100", token.value(), null),
					"read the repositories that may run GitHub Actions");
			List<String> names = new ArrayList<>();
			for (JsonNode repository : selected.path("repositories")) {
				names.add(repository.path("name").asString(""));
			}
			JsonNode total = selected.path("total_count");
			boolean onlyTheRunner = names.size() == 1 && names.getFirst().equalsIgnoreCase(runner)
					&& (total.isMissingNode() || total.asLong(-1) == 1);
			if (!onlyTheRunner) {
				differences.add("GitHub Actions are enabled for " + (names.isEmpty() ? "no repository" : String.join(", ", names))
						+ " instead of the runner " + runner + " only");
			}
		}
		String allowed = actions.path("allowed_actions").asString("");
		if (!"local_only".equals(allowed)) {
			differences.add("GitHub Actions allow " + (allowed.isEmpty() ? "absent" : allowed)
					+ " actions instead of the organization's own only (local_only)");
		}
		JsonNode workflow = require(http().send("GET", "/orgs/" + organization() + "/actions/permissions/workflow",
				token.value(), null), "read the organization's default workflow permissions");
		String permissions = workflow.path("default_workflow_permissions").asString("");
		if (!"read".equals(permissions)) {
			differences.add("the default workflow permissions are " + (permissions.isEmpty() ? "absent" : permissions)
					+ " instead of read");
		}
		JsonNode approve = workflow.path("can_approve_pull_request_reviews");
		if (!approve.isBoolean() || approve.asBoolean()) {
			differences.add("workflows may approve pull requests (can_approve_pull_request_reviews="
					+ (approve.isMissingNode() ? "absent" : approve.asString()) + ") instead of never");
		}
		return differences;
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
	public Set<Long> collaboratorIds(PlatformRepository repository) {
		return this.apps.withToken(App.PLATFORM, List.of(repository.id()), Map.of("administration", "write"), token -> {
			Set<Long> ids = new HashSet<>();
			for (JsonNode user : pages("/repos/" + repository.fullName() + "/collaborators?per_page=100", token.value(),
					"list the collaborators of " + repository.fullName())) {
				if (user.path("id").isNumber()) {
					ids.add(user.path("id").asLong());
				}
			}
			return ids;
		});
	}

	@Override
	public Optional<String> login(long githubUserId) {
		return this.apps.withUnnarrowed(App.PLATFORM, Unnarrowed.ORGANIZATION_READ, token -> {
			GitHubHttp.Answer answer = http().send("GET", "/user/" + githubUserId, token.value(), null);
			if (answer.status() == 404) {
				return Optional.empty();
			}
			String login = require(answer, "read the GitHub account " + githubUserId).path("login").asString("");
			return login.isBlank() ? Optional.empty() : Optional.of(login);
		});
	}

	@Override
	public Map<Long, String> withoutTwoFactor() {
		return this.apps.withUnnarrowed(App.PLATFORM, Unnarrowed.ORGANIZATION_READ, token -> {
			Map<Long, String> users = new java.util.HashMap<>();
			for (JsonNode user : pages("/orgs/" + organization() + "/outside_collaborators?filter=2fa_disabled&per_page=100",
					token.value(), "list collaborators without two-factor authentication")) {
				// An entry without a numeric ID names nobody vibefix can match.
				if (user.path("id").isNumber()) {
					users.put(user.path("id").asLong(), user.path("login").asString("").toLowerCase(Locale.ROOT));
				}
			}
			return users;
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
		return this.apps.withToken(App.PLATFORM, List.of(repository.id()), PULLS_READ, token -> {
			List<PullRequest> open = new ArrayList<>();
			for (JsonNode pull : require(http().send("GET", "/repos/" + repository.fullName() + "/pulls?state=open&per_page=100",
					token.value(), null), "list the pull requests of " + repository.fullName())) {
				JsonNode author = pull.path("user");
				open.add(new PullRequest(pull.path("number").asInt(), author.path("login").asString("").toLowerCase(Locale.ROOT),
						author.path("id").isNumber() ? author.path("id").asLong() : null));
			}
			return open;
		});
	}

	@Override
	public void closePullRequest(PlatformRepository repository, int number) {
		this.apps.withToken(App.PLATFORM, List.of(repository.id()), PULLS_WRITE, token -> {
			require(http().send("PATCH", "/repos/" + repository.fullName() + "/pulls/" + number, token.value(),
					Map.of("state", "closed")), "close pull request " + number);
			return null;
		});
	}

	@Override
	public int openPullRequest(PlatformRepository repository, String head, String base, String title, String body) {
		return this.apps.withToken(App.PLATFORM, List.of(repository.id()), PULLS_WRITE, token -> {
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
	public void mergePullRequest(PlatformRepository repository, int number, String sha, String title) {
		this.apps.withToken(App.PLATFORM, List.of(repository.id()), Map.of("contents", "write", "pull_requests", "write"),
				token -> {
					GitHubHttp.Answer answer = http().send("PUT", "/repos/" + repository.fullName() + "/pulls/" + number + "/merge",
							token.value(), Map.of("sha", sha, "merge_method", "merge", "commit_title", title));
					if (answer.status() == 405 && pullRequestMerged(repository, number)) {
						return null;
					}
					require(answer, "merge pull request " + number);
					return null;
				});
	}

	@Override
	public boolean pullRequestMerged(PlatformRepository repository, int number) {
		return this.apps.withToken(App.PLATFORM, List.of(repository.id()), PULLS_READ,
				token -> require(http().send("GET", "/repos/" + repository.fullName() + "/pulls/" + number, token.value(), null),
						"read pull request " + number).path("merged").asBoolean(false));
	}

	@Override
	public PullRequestState pullRequest(PlatformRepository repository, int number) {
		return this.apps.withToken(App.PLATFORM, List.of(repository.id()), PULLS_READ, token -> {
			JsonNode pull = require(
					http().send("GET", "/repos/" + repository.fullName() + "/pulls/" + number, token.value(), null),
					"read pull request " + number);
			return new PullRequestState(number, pull.path("state").asString(""), pull.path("merged").asBoolean(false),
					pull.path("title").asString(""));
		});
	}

	@Override
	public List<String> pullRequestComments(PlatformRepository repository, int number) {
		// A pull request's conversation is its issue's (GitHub's issue comments API), which Pull requests read covers.
		return this.apps.withToken(App.PLATFORM, List.of(repository.id()), PULLS_READ, token -> {
			List<String> bodies = new ArrayList<>();
			for (JsonNode comment : pages("/repos/" + repository.fullName() + "/issues/" + number + "/comments?per_page=100",
					token.value(), "list the comments of pull request " + number)) {
				bodies.add(comment.path("body").asString(""));
			}
			return bodies;
		});
	}

	@Override
	public void commentOnPullRequest(PlatformRepository repository, int number, String body) {
		this.apps.withToken(App.PLATFORM, List.of(repository.id()), PULLS_WRITE, token -> {
			require(http().send("POST", "/repos/" + repository.fullName() + "/issues/" + number + "/comments", token.value(),
					Map.of("body", body)), "comment on pull request " + number);
			return null;
		});
	}

	@Override
	public void renameAndClosePullRequest(PlatformRepository repository, int number, String title) {
		this.apps.withToken(App.PLATFORM, List.of(repository.id()), PULLS_WRITE, token -> {
			require(http().send("PATCH", "/repos/" + repository.fullName() + "/pulls/" + number, token.value(),
					Map.of("title", title, "state", "closed")), "close pull request " + number);
			return null;
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
	public List<String> settingsDifferences(PlatformRepository repository) {
		// Administration read covers the Actions permissions; nothing is set, only read back (Spike S6).
		return this.apps.withToken(App.PLATFORM, List.of(repository.id()), Map.of("metadata", "read", "administration", "read"),
				token -> {
					String name = repository.fullName();
					JsonNode body = require(http().send("GET", "/repos/" + name, token.value(), null), "read " + name);
					List<String> differences = new ArrayList<>();
					if (!body.path("private").asBoolean(false)) {
						differences.add(name + ": public instead of private");
					}
					for (String feature : FEATURES) {
						if (body.path(feature).asBoolean(false)) {
							differences.add(name + ": " + feature + " is on");
						}
					}
					JsonNode forking = body.path("allow_forking");
					if (!forking.isBoolean() || forking.asBoolean()) {
						differences.add(name + ": allow_forking is " + (forking.isMissingNode() ? "absent" : forking.asString())
								+ " instead of false");
					}
					JsonNode enabled = require(http().send("GET", "/repos/" + name + "/actions/permissions", token.value(), null),
							"read the Actions permissions of " + name).path("enabled");
					if (!enabled.isBoolean() || enabled.asBoolean()) {
						differences.add(name + ": GitHub Actions are " + (enabled.isBoolean() ? "enabled" : "absent")
								+ " instead of disabled");
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
	public Map<String, Set<String>> recentActors(PlatformRepository repository) {
		return this.apps.withToken(App.PLATFORM, List.of(repository.id()), Map.of("metadata", "read"), token -> {
			Map<String, Set<String>> actors = new java.util.TreeMap<>();
			GitHubHttp.Answer answer = http().send("GET", "/repos/" + repository.fullName() + "/events?per_page=30",
					token.value(), null);
			for (JsonNode event : require(answer, "list the events of " + repository.fullName())) {
				actors.computeIfAbsent(event.path("actor").path("login").asString("").toLowerCase(Locale.ROOT),
						login -> new java.util.TreeSet<>())
					.add(event.path("type").asString(""));
			}
			return actors;
		});
	}

	@Override
	public String branchHead(PlatformRepository repository, String branch) {
		return this.apps.withToken(App.PLATFORM, List.of(repository.id()), Map.of("contents", "read"),
				token -> head(repository.fullName(), branch, token));
	}

	/** The branch's ref alone: never a tag of the same name, and no commit body with its files. */
	private String head(String fullName, String branch, InstallationToken token) {
		JsonNode ref = require(http().send("GET", "/repos/" + fullName + "/git/ref/heads/" + branch, token.value(), null),
				"read the head of " + fullName);
		String sha = ref.path("object").path("sha").asString("");
		if (!sha.matches("[0-9a-f]{40}")) {
			throw new GitHubException(0, "The head of " + fullName + " was not a commit SHA");
		}
		return sha;
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
		// The repository is private: the clone gets a token narrowed to it with contents read.
		JsonNode output = this.apps.withToken(App.PLATFORM, List.of(repository.id()), Map.of("contents", "read"), token -> {
			ObjectNode input = JsonNodeFactory.instance.objectNode();
			input.put("repository", repository.fullName());
			input.put("branch", branch);
			input.put("token", token.value());
			try {
				return this.sandbox.run(RepositoryZipJobHandler.TYPE, input).output();
			}
			catch (SandboxJobException ex) {
				throw new GitHubException(0, "The ZIP of " + repository.fullName() + " did not run: " + ex.getMessage());
			}
		});
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

	/** Every item of a paged list, following {@code Link: rel="next"} until it runs out. */
	private List<JsonNode> pages(String path, String token, String what) {
		List<JsonNode> items = new ArrayList<>();
		String next = path;
		for (int page = 0; next != null && page < 100; page++) {
			GitHubHttp.Answer answer = http().send("GET", next, token, null);
			require(answer, what).forEach(items::add);
			next = answer.nextPath(this.apps.properties().apiUrl()).orElse(null);
		}
		return items;
	}

	private static JsonNode require(GitHubHttp.Answer answer, String what) {
		if (!answer.ok()) {
			throw new GitHubException(answer.status(), "GitHub refused to " + what + ": HTTP " + answer.status() + " "
					+ answer.body().path("message").asString(""));
		}
		return answer.body();
	}

}
