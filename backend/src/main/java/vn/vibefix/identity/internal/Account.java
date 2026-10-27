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
 * @param role the stored {@code identity.account.role}, as plain text: what an account from before Google
 * sign-in and an Administrator's row carry, {@code null} for every Google account. Nothing decides on it (Story
 * 20.3); only the session view's deprecated {@code role} and the personal-data export read it, until Story 20.6
 * drops the column
 * @param googleSub Google's subject, the key of a Google account; {@code null} for the others and once
 * erased
 * @param googleEmail the verified Google email read at the last sign-in
 * @param googleName the Google name read at the last sign-in, shown when nothing else names the account
 * @param lockReason why the account is locked, {@code null} while it is not
 */
record Account(UUID id, Kind kind, @Nullable Long githubUserId, @Nullable String githubLogin,
		@Nullable String githubAvatarUrl, @Nullable String role, @Nullable String googleSub, @Nullable String googleEmail,
		@Nullable String googleName, @Nullable Instant lockedAt, @Nullable String lockReason, @Nullable Instant erasedAt,
		Instant createdAt) {

	/**
	 * The role an Administrator's row keeps writing, which {@code account_kind_role_check} requires until Story
	 * 20.6 drops the column.
	 */
	static final String ADMINISTRATOR_ROLE = "ADMINISTRATOR";

	/** A new Administrator's account: no GitHub and no Google identity. */
	static Account administrator(UUID id, Instant createdAt) {
		return new Account(id, Kind.ADMINISTRATOR, null, null, null, ADMINISTRATOR_ROLE, null, null, null, null, null, null,
				createdAt);
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
