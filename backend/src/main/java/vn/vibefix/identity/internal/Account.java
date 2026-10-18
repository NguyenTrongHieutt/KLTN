package vn.vibefix.identity.internal;

import java.time.Instant;
import java.util.UUID;

import org.jspecify.annotations.Nullable;

import vn.vibefix.identity.MentorApprovalStatus;
import vn.vibefix.shared.Role;

/**
 * An account row of {@code identity.account} (AD-19).
 * @param githubUserId GitHub's permanent numeric user ID; {@code null} for Administrators and once
 * erased
 * @param mentorApprovalStatus set exactly for Mentors
 * @param lockReason why the account is locked, {@code null} while it is not
 */
record Account(UUID id, @Nullable Long githubUserId, @Nullable String githubLogin, @Nullable String githubAvatarUrl,
		Role role, @Nullable MentorApprovalStatus mentorApprovalStatus, @Nullable Instant lockedAt,
		@Nullable String lockReason, @Nullable Instant erasedAt, Instant createdAt) {

	boolean isLocked() {
		return this.lockedAt != null;
	}

	boolean isActive() {
		return this.lockedAt == null && this.erasedAt == null;
	}

}
