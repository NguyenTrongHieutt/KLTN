package vn.vibefix.identity.internal;

import java.sql.Timestamp;
import java.util.UUID;

import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.Authentication;

import vn.vibefix.identity.MentorApprovalStatus;
import vn.vibefix.shared.Role;
import vn.vibefix.support.MutableClock;

/**
 * Accounts for tests that need a signed-in caller without the GitHub flow: a row in
 * {@code identity.account} and the authentication a session would hold for it.
 */
public final class IdentityTestSupport {

	private static final java.util.concurrent.atomic.AtomicLong githubIds = new java.util.concurrent.atomic.AtomicLong(
			900_000);

	private IdentityTestSupport() {
	}

	/**
	 * Inserts an account and returns its ID.
	 * @param mentorStatus for a Mentor, its approval status (defaults to {@code APPROVED})
	 * @param lockReason a reason to lock it, or {@code null}
	 */
	public static UUID insertAccount(JdbcClient jdbc, Role role, @Nullable MentorApprovalStatus mentorStatus,
			@Nullable String lockReason) {
		UUID id = UUID.randomUUID();
		Long githubId = role == Role.ADMINISTRATOR ? null : githubIds.incrementAndGet();
		MentorApprovalStatus status = role == Role.MENTOR
				? (mentorStatus == null ? MentorApprovalStatus.APPROVED : mentorStatus) : null;
		Timestamp now = Timestamp.from(MutableClock.DEFAULT_INSTANT);
		jdbc.sql("""
				INSERT INTO identity.account (id, github_user_id, github_login, role, mentor_approval_status, locked_at,
				    lock_reason, created_at)
				VALUES (:id, :githubId, :login, :role, :status, :lockedAt, :lockReason, :now)
				""")
			.param("id", id)
			.param("githubId", githubId)
			.param("login", githubId == null ? null : "user-" + githubId)
			.param("role", role.name())
			.param("status", status == null ? null : status.name())
			.param("lockedAt", lockReason == null ? null : now)
			.param("lockReason", lockReason)
			.param("now", now)
			.update();
		return id;
	}

	/** The authentication a signed-in session holds for the account. */
	public static Authentication authentication(UUID accountId, Role role) {
		return new SignInSession.AccountAuthentication(accountId, role);
	}

	/** Locks an account, as an Administrator will (FR-59). */
	public static void lock(JdbcClient jdbc, UUID accountId, String reason) {
		jdbc.sql("UPDATE identity.account SET locked_at = :at, lock_reason = :reason WHERE id = :id")
			.param("at", Timestamp.from(MutableClock.DEFAULT_INSTANT))
			.param("reason", reason)
			.param("id", accountId)
			.update();
	}

}
