package vn.vibefix.github.internal;

import java.util.List;
import java.util.Map;
import java.util.function.Function;

import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

import vn.vibefix.github.GitHubApps.App;
import vn.vibefix.github.GitHubApps.InstallationToken;
import vn.vibefix.github.PlatformRepositories;
import vn.vibefix.github.SourceApp;

/**
 * The App that dispatches an Actions run, and reads it (AD-44, AD-45, AD-46, Story 23.6a): the Platform App for a run
 * on the runner; for a run of the published {@code vibefix.yml} in a Customer's Source Repository, the Source App
 * through that Customer's own installation ({@link SourceApp#token}). Every token here is narrowed to the one
 * repository the run is in and revoked after its operation, whatever happened. The token the exchange issues is never
 * one of these: the exchange mints it with the Platform App over the grant's platform repositories.
 */
@Component
class DispatchingApps {

	private final GitHubAppsClient apps;

	private final SourceApp source;

	private final PlatformRepositories platform;

	DispatchingApps(GitHubAppsClient apps, SourceApp source, PlatformRepositories platform) {
		this.apps = apps;
		this.source = source;
		this.platform = platform;
	}

	/**
	 * Mints the dispatching App's token narrowed to {@code repositoryId}, runs one operation with it, and revokes it.
	 * @param installationId the Customer's installation, for {@link App#SOURCE}
	 * @throws IllegalArgumentException for a Source App run without its installation
	 * @throws vn.vibefix.github.GitHubException when the App is not configured or GitHub refuses the token
	 */
	<T> T withToken(App app, @Nullable Long installationId, long repositoryId, Map<String, String> permissions,
			Function<InstallationToken, T> operation) {
		return switch (app) {
			case PLATFORM -> this.apps.withToken(App.PLATFORM, List.of(repositoryId), permissions, operation);
			case SOURCE -> {
				if (installationId == null || installationId <= 0) {
					throw new IllegalArgumentException("A run the Source App dispatched names its installation");
				}
				yield this.source.withToken(installationId, repositoryId, permissions, operation);
			}
		};
	}

	/** {@link #withToken(App, Long, long, Map, Function)} for the correlation's run, in its target repository. */
	<T> T withToken(ExchangeCorrelations.Row row, Map<String, String> permissions, Function<InstallationToken, T> operation) {
		return withToken(row.dispatchingApp(), row.sourceInstallationId(), row.repositoryId(), permissions, operation);
	}

	/**
	 * The App's bot, the triggering actor of every run it dispatches.
	 * @throws vn.vibefix.github.GitHubException when the App's registration cannot be read
	 */
	String botLogin(App app) {
		return switch (app) {
			case PLATFORM -> this.platform.botLogin();
			case SOURCE -> this.source.botLogin();
		};
	}

	/**
	 * The Source App's dispatch token (Story 23.6a): Actions write to dispatch the run, and Contents read. Every Source
	 * App token of a run holds Contents read because the run's read-back reads the workflow blob at the run's head, and
	 * Contents read is the narrowest permission that reads a private repository's files.
	 */
	static final Map<String, String> SOURCE_DISPATCH = Map.of("actions", "write", "contents", "read");

	/** The Source App's run lookup and read: Actions read, and Contents read for the reason {@link #SOURCE_DISPATCH} gives. */
	static final Map<String, String> SOURCE_RUN_READ = Map.of("actions", "read", "contents", "read");

	/**
	 * What a run lookup or read needs of the dispatching App: Actions read, and for the Source App Contents read too
	 * ({@link #SOURCE_RUN_READ}).
	 */
	static Map<String, String> runRead(App app) {
		return app == App.SOURCE ? SOURCE_RUN_READ : Map.of("actions", "read");
	}

}
