package vn.vibefix.github.internal;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;
import vn.vibefix.github.GitHubApps.App;
import vn.vibefix.github.GitHubApps.InstallationToken;
import vn.vibefix.github.GitHubException;
import vn.vibefix.github.MergeApp;
import vn.vibefix.shared.SandboxJobException;
import vn.vibefix.shared.SandboxJobs;

/**
 * {@link MergeApp} on GitHub's REST API (AD-10). The installation and the install requests are read with
 * the Merge App's JWT; the selection with a Metadata-read token from that installation, revoked after
 * the read whatever happened. The install link's slug is the App's own, read once from {@code GET /app}.
 */
@Component
class GitHubMergeApp implements MergeApp {

	/** The selection read's one permission. */
	static final Map<String, String> SELECTION_READ = Map.of("metadata", "read");

	private static final int PAGE = 100;

	private final GitHubAppsClient apps;

	private final SandboxJobs sandbox;

	private volatile @Nullable String slug;

	GitHubMergeApp(GitHubAppsClient apps, SandboxJobs sandbox) {
		this.apps = apps;
		this.sandbox = sandbox;
	}

	@Override
	public Optional<Installation> installation(long installationId) {
		GitHubHttp.Answer answer = this.apps.http()
			.send("GET", "/app/installations/" + installationId, this.apps.jwt(App.MERGE), null);
		if (answer.status() == 404) {
			return Optional.empty();
		}
		JsonNode body = require(answer, "read installation " + installationId);
		boolean all = "all".equals(body.path("repository_selection").asString(""));
		boolean suspended = !body.path("suspended_at").asString("").isBlank();
		List<SelectedRepository> repositories = all || suspended ? List.of() : selection(installationId);
		return Optional.of(new Installation(installationId, body.path("account").path("id").asLong(),
				body.path("account").path("login").asString(""), all, suspended, repositories));
	}

	@Override
	public List<InstallRequest> requests() {
		List<InstallRequest> requests = new ArrayList<>();
		for (int page = 1;; page++) {
			JsonNode body = require(this.apps.http()
				.send("GET", "/app/installation-requests?per_page=" + PAGE + "&page=" + page, this.apps.jwt(App.MERGE), null),
					"list installation requests");
			for (JsonNode request : body) {
				requests.add(new InstallRequest(request.path("id").asLong(), request.path("account").path("id").asLong(),
						request.path("account").path("login").asString(""), request.path("requester").path("id").asLong()));
			}
			if (body.size() < PAGE) {
				return requests;
			}
		}
	}

	@Override
	public void remove(long installationId) {
		GitHubHttp.Answer answer = this.apps.http()
			.send("DELETE", "/app/installations/" + installationId, this.apps.jwt(App.MERGE), null);
		if (answer.status() != 404) {
			require(answer, "remove installation " + installationId);
		}
	}

	@Override
	public String installUrl(String state) {
		String known = this.slug;
		if (known == null) {
			known = this.apps.registration(App.MERGE).slug();
			this.slug = known;
		}
		String base = this.apps.properties().gitUrl().toString().replaceAll("/+$", "");
		return base + "/apps/" + known + "/installations/new?state=" + URLEncoder.encode(state, StandardCharsets.UTF_8);
	}

	@Override
	public InstallationToken token(long installationId, long repositoryId, Map<String, String> permissions) {
		return this.apps.mintFrom(App.MERGE, installationId, List.of(repositoryId), permissions);
	}

	@Override
	public void revoke(InstallationToken token) {
		this.apps.revoke(token);
	}

	@Override
	public List<String> workflowFiles(InstallationToken token, String fullName, String branch) {
		GitHubHttp.Answer answer = this.apps.http()
			.send("GET", "/repos/" + fullName + "/contents/.github/workflows?ref=" + encode(branch), token.value(), null);
		if (answer.status() == 404) {
			return List.of();
		}
		List<String> files = new ArrayList<>();
		for (JsonNode entry : require(answer, "list the workflow files of " + fullName)) {
			if ("file".equals(entry.path("type").asString(""))) {
				files.add(entry.path("path").asString(""));
			}
		}
		return files;
	}

	@Override
	public TrialMerge trialMerge(String sourceFullName, String sourceBranch, String intermediaryFullName,
			String intermediaryBranch, String sha) {
		ObjectNode input = JsonNodeFactory.instance.objectNode();
		input.put("source", sourceFullName);
		input.put("sourceBranch", sourceBranch);
		input.put("intermediary", intermediaryFullName);
		input.put("intermediaryBranch", intermediaryBranch);
		input.put("sha", sha);
		JsonNode output;
		try {
			output = this.sandbox.run(FinalMergeTrialJobHandler.TYPE, input).output();
		}
		catch (SandboxJobException ex) {
			throw new GitHubException(0, "The trial merge into " + sourceFullName + " did not run: " + ex.getMessage());
		}
		List<String> conflicts = new ArrayList<>();
		output.path("conflicts").forEach(path -> conflicts.add(path.asString("")));
		return new TrialMerge(output.path("clean").asBoolean(false), output.path("moved").asBoolean(false), conflicts);
	}

	@Override
	public boolean push(InstallationToken token, String intermediaryFullName, String intermediaryBranch, String sha,
			String sourceFullName, String branch) {
		ObjectNode input = JsonNodeFactory.instance.objectNode();
		input.put("source", intermediaryFullName);
		input.put("sha", sha);
		input.put("sourceBranch", intermediaryBranch);
		input.put("branch", branch);
		input.put("target", sourceFullName);
		input.put("token", token.value());
		try {
			return sha.equals(this.sandbox.run(RepositoryCopyJobHandler.TYPE, input).output().path("pushed").asString(""));
		}
		catch (SandboxJobException ex) {
			throw new GitHubException(0, "The push to " + sourceFullName + " did not run: " + ex.getMessage());
		}
	}

	@Override
	public Optional<PullRequest> findPull(InstallationToken token, String fullName, String branch) {
		String owner = fullName.substring(0, fullName.indexOf('/'));
		JsonNode pulls = require(this.apps.http()
			.send("GET", "/repos/" + fullName + "/pulls?state=open&head=" + encode(owner + ":" + branch), token.value(), null),
				"list the pull requests of " + fullName);
		for (JsonNode pull : pulls) {
			return Optional.of(new PullRequest(pull.path("number").asInt(), pull.path("html_url").asString("")));
		}
		return Optional.empty();
	}

	@Override
	public PullRequest openPull(InstallationToken token, String fullName, String branch, String base, String title,
			String body) {
		JsonNode pull = require(this.apps.http()
			.send("POST", "/repos/" + fullName + "/pulls", token.value(),
					Map.of("head", branch, "base", base, "title", title, "body", body, "maintainer_can_modify", false)),
				"open a pull request on " + fullName);
		return new PullRequest(pull.path("number").asInt(), pull.path("html_url").asString(""));
	}

	@Override
	public MergeAnswer mergePull(InstallationToken token, String fullName, int number, String sha, String title) {
		GitHubHttp.Answer answer = this.apps.http()
			.send("PUT", "/repos/" + fullName + "/pulls/" + number + "/merge", token.value(),
					Map.of("sha", sha, "merge_method", "merge", "commit_title", title));
		if (answer.ok()) {
			return MergeAnswer.MERGED;
		}
		if (answer.status() == 405 && pullMerged(token, fullName, number)) {
			return MergeAnswer.MERGED;
		}
		// SG-15: an archived repository (403), a ruleset or required checks (405, 422) do not pass by waiting.
		return answer.status() == 403 || answer.status() == 405 || answer.status() == 422 ? MergeAnswer.REFUSED
				: MergeAnswer.TRANSIENT;
	}

	@Override
	public boolean pullMerged(InstallationToken token, String fullName, int number) {
		return require(this.apps.http().send("GET", "/repos/" + fullName + "/pulls/" + number, token.value(), null),
				"read pull request " + number + " of " + fullName).path("merged").asBoolean(false);
	}

	@Override
	public void deleteBranch(InstallationToken token, String fullName, String branch) {
		GitHubHttp.Answer answer = this.apps.http()
			.send("DELETE", "/repos/" + fullName + "/git/refs/heads/" + branch, token.value(), null);
		if (answer.status() != 404 && answer.status() != 422) {
			require(answer, "delete branch " + branch + " of " + fullName);
		}
	}

	private static String encode(String value) {
		return URLEncoder.encode(value, StandardCharsets.UTF_8);
	}

	/** Every selected repository, read with a Metadata-read token from the installation itself. */
	private List<SelectedRepository> selection(long installationId) {
		InstallationToken token = this.apps.mintFrom(App.MERGE, installationId, List.of(), SELECTION_READ);
		try {
			List<SelectedRepository> repositories = new ArrayList<>();
			for (int page = 1;; page++) {
				JsonNode body = require(this.apps.http()
					.send("GET", "/installation/repositories?per_page=" + PAGE + "&page=" + page, token.value(), null),
						"read the selection of installation " + installationId);
				JsonNode listed = body.path("repositories");
				for (JsonNode repository : listed) {
					repositories.add(new SelectedRepository(repository.path("id").asLong(),
							repository.path("full_name").asString(""), repository.path("owner").path("id").asLong()));
				}
				if (listed.size() < PAGE) {
					return repositories;
				}
			}
		}
		finally {
			this.apps.revoke(token);
		}
	}

	private static JsonNode require(GitHubHttp.Answer answer, String what) {
		if (!answer.ok()) {
			throw new GitHubException(answer.status(), "GitHub refused to " + what + ": HTTP " + answer.status());
		}
		return answer.body();
	}

}
