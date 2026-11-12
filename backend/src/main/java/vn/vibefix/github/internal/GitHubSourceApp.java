package vn.vibefix.github.internal;

import java.io.IOException;
import java.io.InputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

import tools.jackson.databind.JsonNode;
import vn.vibefix.github.GitHubApps.App;
import vn.vibefix.github.GitHubApps.InstallationToken;
import vn.vibefix.github.GitHubException;
import vn.vibefix.github.SourceApp;

/**
 * {@link SourceApp} on GitHub's REST API (AD-10, AD-45). The installation is read with the Source App's JWT; its
 * selection with a Metadata-read token from that installation, revoked after the read whatever happened. The
 * install link's slug is the App's own, read once from {@code GET /app}. The published workflow is the classpath
 * copy {@value #RESOURCE} of {@code deploy/customer/vibefix.yml}, byte for byte; "Kiểm tra" compares a file with
 * the accepted set through {@link AcceptedWorkflows}, CRLF read as LF.
 */
@Component
class GitHubSourceApp implements SourceApp {

	/** Where the Customer adds the published workflow. */
	static final String WORKFLOW_PATH = ".github/workflows/vibefix.yml";

	/** The classpath copy of {@code deploy/customer/vibefix.yml}. */
	static final String RESOURCE = "github/vibefix.yml";

	/** The selection read's one permission. */
	static final Map<String, String> SELECTION_READ = Map.of("metadata", "read");

	private static final int PAGE = 100;

	private final GitHubAppsClient apps;

	private final AcceptedWorkflows accepted;

	private volatile @Nullable String slug;

	private volatile @Nullable PublishedWorkflow published;

	GitHubSourceApp(GitHubAppsClient apps, Clock clock) {
		this.apps = apps;
		this.accepted = new AcceptedWorkflows(apps.properties(), clock);
	}

	@Override
	public boolean configured() {
		return this.apps.isConfigured(App.SOURCE);
	}

	@Override
	public Optional<Installation> installationOn(String owner, String name) {
		GitHubHttp.Answer answer = this.apps.http()
			.send("GET", "/repos/" + owner + "/" + name + "/installation", this.apps.jwt(App.SOURCE), null);
		if (answer.status() == 404) {
			return Optional.empty();
		}
		return Optional.of(installation(require(answer, "read the installation on " + owner + "/" + name)));
	}

	@Override
	public Optional<Installation> installation(long installationId) {
		GitHubHttp.Answer answer = this.apps.http()
			.send("GET", "/app/installations/" + installationId, this.apps.jwt(App.SOURCE), null);
		if (answer.status() == 404) {
			return Optional.empty();
		}
		return Optional.of(installation(require(answer, "read installation " + installationId)));
	}

	@Override
	public String installUrl(String state) {
		String base = this.apps.properties().gitUrl().toString().replaceAll("/+$", "");
		return base + "/apps/" + slug() + "/installations/new?state=" + URLEncoder.encode(state, StandardCharsets.UTF_8);
	}

	@Override
	public String botLogin() {
		return slug() + "[bot]";
	}

	@Override
	public String settingsUrl(String accountType, String accountLogin, long installationId) {
		String base = this.apps.properties().gitUrl().toString().replaceAll("/+$", "");
		return "Organization".equals(accountType)
				? base + "/organizations/" + URLEncoder.encode(accountLogin, StandardCharsets.UTF_8) + "/settings/installations/"
						+ installationId
				: base + "/settings/installations/" + installationId;
	}

	@Override
	public InstallationToken token(long installationId, long repositoryId, Map<String, String> permissions) {
		return this.apps.mintFrom(App.SOURCE, installationId, List.of(repositoryId), permissions);
	}

	@Override
	public void revoke(InstallationToken token) {
		this.apps.revoke(token);
	}

	@Override
	public PublishedWorkflow publishedWorkflow() {
		PublishedWorkflow known = this.published;
		if (known == null) {
			try (InputStream in = GitHubSourceApp.class.getClassLoader().getResourceAsStream(RESOURCE)) {
				if (in == null) {
					throw new IllegalStateException(RESOURCE + " is missing from the classpath");
				}
				String content = new String(in.readAllBytes(), StandardCharsets.UTF_8).replace("\r\n", "\n");
				known = new PublishedWorkflow(WORKFLOW_PATH, content, AcceptedWorkflows.blobSha(content));
			}
			catch (IOException ex) {
				throw new IllegalStateException(RESOURCE + " cannot be read", ex);
			}
			this.published = known;
		}
		return known;
	}

	@Override
	public WorkflowCheck workflow(InstallationToken token, String fullName, String branch) {
		GitHubHttp.Answer file = this.apps.http()
			.send("GET", "/repos/" + fullName + "/contents/" + WORKFLOW_PATH + "?ref="
					+ URLEncoder.encode(branch, StandardCharsets.UTF_8), token.value(), null);
		AcceptedWorkflows.Checked checked = this.accepted.check(WORKFLOW_PATH, file);
		return switch (checked.verdict()) {
			case ACCEPTED -> new WorkflowCheck(WorkflowVerdict.MATCHES, false);
			case MISSING -> new WorkflowCheck(WorkflowVerdict.MISSING, false);
			case MISMATCH -> new WorkflowCheck(WorkflowVerdict.MISMATCH, carriageReturn(file.body()));
		};
	}

	private String slug() {
		String known = this.slug;
		if (known == null) {
			known = this.apps.registration(App.SOURCE).slug();
			this.slug = known;
		}
		return known;
	}

	private Installation installation(JsonNode body) {
		long id = body.path("id").asLong();
		boolean all = "all".equals(body.path("repository_selection").asString(""));
		JsonNode suspendedAt = body.path("suspended_at");
		boolean suspended = suspendedAt.isString() && !suspendedAt.asString().isBlank();
		Map<String, String> permissions = new LinkedHashMap<>();
		body.path("permissions").properties().forEach(entry -> permissions.put(entry.getKey(), entry.getValue().asString("")));
		JsonNode account = body.path("account");
		List<SelectedRepository> repositories = all || suspended ? List.of() : selection(id);
		return new Installation(id, account.path("id").asLong(), account.path("login").asString(""),
				account.path("type").asString("User"), all, suspended, permissions, repositories);
	}

	/** Every selected repository, read with a Metadata-read token from the installation itself. */
	private List<SelectedRepository> selection(long installationId) {
		InstallationToken token = this.apps.mintFrom(App.SOURCE, installationId, List.of(), SELECTION_READ);
		try {
			List<SelectedRepository> repositories = new ArrayList<>();
			for (int page = 1;; page++) {
				JsonNode body = require(this.apps.http()
					.send("GET", "/installation/repositories?per_page=" + PAGE + "&page=" + page, token.value(), null),
						"read the selection of installation " + installationId);
				JsonNode listed = body.path("repositories");
				for (JsonNode repository : listed) {
					repositories.add(new SelectedRepository(repository.path("id").asLong(),
							repository.path("full_name").asString("")));
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

	/** Whether the base64 content GitHub sent holds a carriage return. */
	private static boolean carriageReturn(JsonNode body) {
		if (!body.path("content").isString()) {
			return false;
		}
		try {
			byte[] bytes = Base64.getMimeDecoder().decode(body.path("content").asString());
			for (byte b : bytes) {
				if (b == '\r') {
					return true;
				}
			}
			return false;
		}
		catch (IllegalArgumentException ex) {
			return false;
		}
	}

	private static JsonNode require(GitHubHttp.Answer answer, String what) {
		if (!answer.ok()) {
			throw new GitHubException(answer.status(), "GitHub refused to " + what + ": HTTP " + answer.status());
		}
		return answer.body();
	}

}
