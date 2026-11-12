package vn.vibefix.github.internal;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Component;

import vn.vibefix.github.GitHubApps.InstallationToken;

/**
 * The tokens the Actions exchange issued and has not revoked yet, keyed by their correlation (AD-44, AD-22).
 * They live only in memory: a token can be revoked only by presenting it, and no token is ever stored, so
 * a restart forgets them and the revocation poller marks their correlations {@code REVOKE_UNKNOWN}.
 */
@Component
class IssuedTokens {

	private final Map<UUID, InstallationToken> tokens = new ConcurrentHashMap<>();

	void put(UUID correlation, InstallationToken token) {
		this.tokens.put(correlation, token);
	}

	Optional<InstallationToken> get(UUID correlation) {
		return Optional.ofNullable(this.tokens.get(correlation));
	}

	void remove(UUID correlation) {
		this.tokens.remove(correlation);
	}

	/** Forgets every token, as a restart does; for tests of what the poller does then. */
	void forgetAll() {
		this.tokens.clear();
	}

}
