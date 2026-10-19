package vn.vibefix.github;

import java.time.Instant;
import java.util.Collection;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * The platform's GitHub Apps (AD-10): installation tokens for one operation each, and the Apps'
 * registrations as GitHub reports them.
 */
public interface GitHubApps {

	/** The two GitHub Apps of an environment; the sign-in OAuth App lives in {@code identity}. */
	enum App {

		/** Installed only on the platform organization: copies, forks, rulesets, invitations. */
		PLATFORM,

		/** Installed by a Customer on their Source Repository for one Final Merge. */
		MERGE

	}

	/**
	 * An installation token. Its value is never printed: {@link #toString()} leaves it out.
	 * @param repositoryIds the repositories it is narrowed to; empty for an organization-level token
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
	 * Mints a token of the App's installation for one operation.
	 * @param repositoryIds the numeric repository IDs it may reach; empty for none (organization reads)
	 * @param permissions only what the operation needs, for example {@code contents: write}
	 * @throws GitHubException when the App is not configured or GitHub refuses
	 */
	InstallationToken token(App app, Collection<Long> repositoryIds, Map<String, String> permissions);

	/** Revokes the token at once; one already expired or revoked changes nothing. */
	void revoke(InstallationToken token);

	/**
	 * The App's registration, read with its JWT ({@code GET /app}).
	 * @throws GitHubException when the App is not configured or GitHub refuses
	 */
	Registration registration(App app);

}
