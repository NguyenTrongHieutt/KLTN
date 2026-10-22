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
import vn.vibefix.github.GitHubApps.App;
import vn.vibefix.github.GitHubApps.InstallationToken;
import vn.vibefix.github.GitHubException;
import vn.vibefix.github.MergeApp;

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

	private volatile @Nullable String slug;

	GitHubMergeApp(GitHubAppsClient apps) {
		this.apps = apps;
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
