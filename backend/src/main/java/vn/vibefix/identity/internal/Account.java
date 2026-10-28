package vn.vibefix.identity.internal;

import java.time.Instant;
import java.util.UUID;

import org.jspecify.annotations.Nullable;

import vn.vibefix.shared.Kind;

/**
 * An account row of {@code identity.account} (AD-19).
 * @param kind a person's account or an Administrator's
 * @param githubUserId GitHub's permanent numeric user ID; {@code null} for Administrators, for a
 * Google account until Story 19.2 links GitHub, and once erased
 * @param googleSub Google's subject, the key of a Google account; {@code null} for the others and once
 * erased
 * @param googleEmail the verified Google email read at the last sign-in
 * @param googleName the Google name read at the last sign-in, shown when nothing else names the account
 * @param lockReason why the account is locked, {@code null} while it is not
 */
record Account(UUID id, Kind kind, @Nullable Long githubUserId, @Nullable String githubLogin,
		@Nullable String githubAvatarUrl, @Nullable String googleSub, @Nullable String googleEmail,
		@Nullable String googleName, @Nullable Instant lockedAt, @Nullable String lockReason, @Nullable Instant erasedAt,
		Instant createdAt) {

	/** A new Administrator's account: no GitHub and no Google identity, and no role, which no account has. */
	static Account administrator(UUID id, Instant createdAt) {
		return new Account(id, Kind.ADMINISTRATOR, null, null, null, null, null, null, null, null, null, createdAt);
	}

	boolean isLocked() {
		return this.lockedAt != null;
	}

	boolean isActive() {
		return this.lockedAt == null && this.erasedAt == null;
	}

	/** The name to greet the account by: its Google name, else its GitHub login, else its Google email. */
	@Nullable String displayName() {
		if (this.googleName != null && !this.googleName.isBlank()) {
			return this.googleName;
		}
		return this.githubLogin != null ? this.githubLogin : this.googleEmail;
	}

}
