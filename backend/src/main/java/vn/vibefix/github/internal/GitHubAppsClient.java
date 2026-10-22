package vn.vibefix.github.internal;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Clock;
import java.time.Instant;
import java.util.Collection;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Component;

import tools.jackson.databind.JsonNode;
import vn.vibefix.github.GitHubApps;
import vn.vibefix.github.GitHubException;
import vn.vibefix.github.internal.GitHubProperties.AppProperties;

/**
 * {@link GitHubApps} on GitHub's REST API (AD-10). Each App's key is read once from its secret
 * file; tokens are minted with {@code repository_ids} and {@code permissions} and revoked through
 * {@code DELETE /installation/token}. A narrowed token names 1 to 500 repositories, and an unnarrowed
 * one only an {@link Unnarrowed} purpose's fixed permissions.
 */
@Component
class GitHubAppsClient implements GitHubApps {

	private final GitHubProperties properties;

	private final GitHubHttp http;

	private final Clock clock;

	private final Map<App, AppJwt> keys = new EnumMap<>(App.class);

	GitHubAppsClient(GitHubProperties properties, Clock clock) {
		this.properties = properties;
		this.http = new GitHubHttp(properties.apiUrl());
		this.clock = clock;
	}

	@Override
	public InstallationToken token(App app, Collection<Long> repositoryIds, Map<String, String> permissions) {
		if (repositoryIds.isEmpty() || repositoryIds.size() > MAX_REPOSITORIES) {
			throw new IllegalArgumentException("A narrowed token names 1 to " + MAX_REPOSITORIES + " repositories, not "
					+ repositoryIds.size() + ": an unnarrowed one is one of AD-10's Unnarrowed purposes");
		}
		return mint(app, repositoryIds, permissions);
	}

	@Override
	public InstallationToken unnarrowed(App app, Unnarrowed purpose) {
		return mint(app, List.of(), purpose.permissions());
	}

	/**
	 * Mints any token, unchecked: {@link #token} and {@link #unnarrowed} hold AD-10's rules, and only the
	 * dev-only S4 probe calls this directly.
	 */
	InstallationToken mint(App app, Collection<Long> repositoryIds, Map<String, String> permissions) {
		AppProperties configured = configured(app);
		if (configured.installationId() == null) {
			throw new GitHubException(0, propertyName(app) + ".installation-id is not configured");
		}
		return mintFrom(app, configured.installationId(), repositoryIds, permissions);
	}

	/**
	 * Mints a token from one installation, unchecked: the Merge App's installations are the Customers'
	 * (AD-10), so {@link GitHubMergeApp} names the installation and holds its rules.
	 */
	InstallationToken mintFrom(App app, long installationId, Collection<Long> repositoryIds, Map<String, String> permissions) {
		Map<String, Object> body = new LinkedHashMap<>();
		if (!repositoryIds.isEmpty()) {
			body.put("repository_ids", List.copyOf(repositoryIds));
		}
		body.put("permissions", Map.copyOf(permissions));
		GitHubHttp.Answer answer = this.http.send("POST",
				"/app/installations/" + installationId + "/access_tokens", jwt(app), body);
		if (answer.status() != 201) {
			throw new GitHubException(answer.status(), "GitHub refused the installation token: HTTP " + answer.status()
					+ " " + answer.body().path("message").asString(""));
		}
		return new InstallationToken(answer.body().path("token").asString(),
				Instant.parse(answer.body().path("expires_at").asString()), new HashSet<>(repositoryIds));
	}

	@Override
	public void revoke(InstallationToken token) {
		this.http.send("DELETE", "/installation/token", token.value(), null);
	}

	@Override
	public Registration registration(App app) {
		GitHubHttp.Answer answer = this.http.send("GET", "/app", jwt(app), null);
		if (answer.status() != 200) {
			throw new GitHubException(answer.status(), "GitHub refused GET /app: HTTP " + answer.status());
		}
		Map<String, String> permissions = new LinkedHashMap<>();
		answer.body().path("permissions").properties().forEach(entry -> permissions.put(entry.getKey(),
				entry.getValue().asString()));
		Set<String> events = new HashSet<>();
		for (JsonNode event : answer.body().path("events")) {
			events.add(event.asString());
		}
		return new Registration(answer.body().path("slug").asString(""), permissions, events);
	}

	/** The installation as the App's JWT reads it ({@code GET /app/installations/{id}}), for the spike. */
	GitHubHttp.Answer installation(App app) {
		AppProperties configured = configured(app);
		return this.http.send("GET", "/app/installations/" + configured.installationId(), jwt(app), null);
	}

	/** The App's numeric ID, as a ruleset names its bypass actor. */
	long appId(App app) {
		return configured(app).id();
	}

	GitHubHttp http() {
		return this.http;
	}

	GitHubProperties properties() {
		return this.properties;
	}

	/** Whether the App has an ID configured, so the daily jobs read it. */
	boolean isConfigured(App app) {
		AppProperties configured = app == App.PLATFORM ? this.properties.platformApp() : this.properties.mergeApp();
		return configured != null && configured.id() != null && configured.keyFile() != null;
	}

	String jwt(App app) {
		return key(app).sign(this.clock.instant());
	}

	private synchronized AppJwt key(App app) {
		AppJwt key = this.keys.get(app);
		if (key == null) {
			AppProperties configured = configured(app);
			if (configured.keyFile() == null) {
				throw new GitHubException(0, propertyName(app) + ".key-file is not configured");
			}
			try {
				key = AppJwt.of(configured.id(), Files.readString(configured.keyFile(), StandardCharsets.US_ASCII));
			}
			catch (IOException ex) {
				throw new GitHubException(0, propertyName(app) + ".key-file cannot be read");
			}
			this.keys.put(app, key);
		}
		return key;
	}

	private AppProperties configured(App app) {
		AppProperties configured = app == App.PLATFORM ? this.properties.platformApp() : this.properties.mergeApp();
		if (configured == null || configured.id() == null) {
			throw new GitHubException(0, propertyName(app) + ".id is not configured");
		}
		return configured;
	}

	private static String propertyName(App app) {
		return app == App.PLATFORM ? "vibefix.github.platform-app" : "vibefix.github.merge-app";
	}

}
