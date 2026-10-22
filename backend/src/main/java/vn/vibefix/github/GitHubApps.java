package vn.vibefix.github;

import java.time.Instant;
import java.util.Collection;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;

/**
 * The platform's GitHub Apps (AD-10): installation tokens for one operation each, and the Apps'
 * registrations as GitHub reports them. A token is narrowed by {@code repository_ids} whenever its call
 * targets existing repositories; only AD-10's listed purposes get an unnarrowed one, and none of those
 * enters the sandbox (AD-32).
 */
public interface GitHubApps {

	/** The two GitHub Apps of an environment; the sign-in OAuth App lives in {@code identity}. */
	enum App {

		/** Installed only on the platform organization: copies, forks, rulesets, invitations. */
		PLATFORM,

		/** Installed by a Customer on their Source Repository for one Final Merge. */
		MERGE

	}

	/** The most repositories one token may name (GitHub's limit for {@code repository_ids}). */
	int MAX_REPOSITORIES = 500;

	/**
	 * AD-10's unnarrowed tokens, each for one call and with its fixed permissions (Spike S4).
	 * The Merge App's selection read names a Customer's installation, so it lives in {@link MergeApp}.
	 */
	enum Unnarrowed {

		/** The J7 Intermediary Repository: it does not exist yet, so nothing can narrow the token. */
		REPOSITORY_CREATION(Map.of("administration", "write")),

		/** The create-fork call: GitHub refuses a fork token narrowed by {@code repository_ids} (403). */
		FORK(Map.of("administration", "write", "contents", "read")),

		/** Organization settings, Actions permissions, and members. */
		ORGANIZATION_READ(Map.of("organization_administration", "read", "members", "read"));

		private final Map<String, String> permissions;

		Unnarrowed(Map<String, String> permissions) {
			this.permissions = permissions;
		}

		public Map<String, String> permissions() {
			return this.permissions;
		}

	}

	/**
	 * An installation token. Its value is never printed: {@link #toString()} leaves it out.
	 * @param repositoryIds the repositories it is narrowed to; empty only for an {@link Unnarrowed} token
	 */
	record InstallationToken(String value, Instant expiresAt, Set<Long> repositoryIds) {

		public InstallationToken {
			Objects.requireNonNull(value, "value");
			repositoryIds = Set.copyOf(repositoryIds);
		}

		@Override
		public String toString() {
			return "InstallationToken[expiresAt=" + this.expiresAt + ", repositoryIds=" + this.repositoryIds + "]";
		}

	}

	/** An App as GitHub reports it: its slug, permissions ({@code contents: write}), and events. */
	record Registration(String slug, Map<String, String> permissions, Set<String> events) {

		public Registration {
			permissions = Map.copyOf(permissions);
			events = Set.copyOf(events);
		}

	}

	/**
	 * Mints a token of the App's installation for one operation on existing repositories.
	 * @param repositoryIds the numeric repository IDs it may reach: 1 to {@value #MAX_REPOSITORIES}
	 * @param permissions only what the operation needs, for example {@code contents: write}
	 * @throws IllegalArgumentException for no repository, or more than {@value #MAX_REPOSITORIES}: an empty set
	 * would reach every repository of the installation
	 * @throws GitHubException when the App is not configured or GitHub refuses
	 */
	InstallationToken token(App app, Collection<Long> repositoryIds, Map<String, String> permissions);

	/**
	 * Mints one of AD-10's unnarrowed tokens for one call.
	 * @throws GitHubException when the App is not configured or GitHub refuses
	 */
	InstallationToken unnarrowed(App app, Unnarrowed purpose);

	/** Revokes the token at once; one already expired or revoked changes nothing. */
	void revoke(InstallationToken token);

	/** Mints a narrowed token, runs one operation with it, and revokes it whatever happened. */
	default <T> T withToken(App app, Collection<Long> repositoryIds, Map<String, String> permissions,
			Function<InstallationToken, T> operation) {
		InstallationToken token = token(app, repositoryIds, permissions);
		try {
			return operation.apply(token);
		}
		finally {
			revoke(token);
		}
	}

	/** Mints an unnarrowed token, runs its one call, and revokes it whatever happened. */
	default <T> T withUnnarrowed(App app, Unnarrowed purpose, Function<InstallationToken, T> call) {
		InstallationToken token = unnarrowed(app, purpose);
		try {
			return call.apply(token);
		}
		finally {
			revoke(token);
		}
	}

	/**
	 * The App's registration, read with its JWT ({@code GET /app}).
	 * @throws GitHubException when the App is not configured or GitHub refuses
	 */
	Registration registration(App app);

}
