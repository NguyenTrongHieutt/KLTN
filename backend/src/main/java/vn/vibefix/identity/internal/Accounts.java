package vn.vibefix.identity.internal;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import vn.vibefix.identity.MentorApprovalStatus;
import vn.vibefix.shared.Role;

/** Reads and writes {@code identity.account}, {@code identity.acknowledgment}, and the Customer token. */
@Component
class Accounts {

	private final JdbcClient jdbc;

	Accounts(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	Optional<Account> findById(UUID id) {
		return this.jdbc.sql("SELECT * FROM identity.account WHERE id = :id").param("id", id).query(Accounts::row)
			.optional();
	}

	Optional<Account> findByGitHubUserId(long githubUserId) {
		return this.jdbc.sql("SELECT * FROM identity.account WHERE github_user_id = :githubUserId")
			.param("githubUserId", githubUserId)
			.query(Accounts::row)
			.optional();
	}

	void insert(Account account) {
		this.jdbc.sql("""
				INSERT INTO identity.account
				    (id, github_user_id, github_login, github_avatar_url, role, mentor_approval_status, created_at, signed_in_at)
				VALUES (:id, :githubUserId, :githubLogin, :githubAvatarUrl, :role, :mentorApprovalStatus, :createdAt, :createdAt)
				""")
			.param("id", account.id())
			.param("githubUserId", account.githubUserId())
			.param("githubLogin", account.githubLogin())
			.param("githubAvatarUrl", account.githubAvatarUrl())
			.param("role", account.role().name())
			.param("mentorApprovalStatus",
					account.mentorApprovalStatus() == null ? null : account.mentorApprovalStatus().name())
			.param("createdAt", Timestamp.from(account.createdAt()))
			.update();
	}

	/** A sign-in refreshes the GitHub login and avatar, which may have changed since the last one. */
	void recordSignIn(UUID id, String githubLogin, @Nullable String githubAvatarUrl, Instant at) {
		this.jdbc.sql("""
				UPDATE identity.account SET github_login = :login, github_avatar_url = :avatar, signed_in_at = :at
				WHERE id = :id
				""")
			.param("id", id)
			.param("login", githubLogin)
			.param("avatar", githubAvatarUrl)
			.param("at", Timestamp.from(at))
			.update();
	}

	void insertAcknowledgment(UUID id, UUID accountId, String notice, String noticeVersion, Instant at) {
		this.jdbc.sql("""
				INSERT INTO identity.acknowledgment (id, account_id, notice, notice_version, acknowledged_at)
				VALUES (:id, :accountId, :notice, :version, :at)
				""")
			.param("id", id)
			.param("accountId", accountId)
			.param("notice", notice)
			.param("version", noticeVersion)
			.param("at", Timestamp.from(at))
			.update();
	}

	/** Stores or replaces the Customer's sealed sign-in token (AD-22, SG-9). */
	void storeCustomerToken(UUID accountId, TokenCipher.Sealed sealed, Instant at) {
		this.jdbc.sql("""
				INSERT INTO identity.customer_token (account_id, key_version, nonce, ciphertext, stored_at)
				VALUES (:accountId, :keyVersion, :nonce, :ciphertext, :at)
				ON CONFLICT (account_id) DO UPDATE
				SET key_version = EXCLUDED.key_version, nonce = EXCLUDED.nonce, ciphertext = EXCLUDED.ciphertext,
				    stored_at = EXCLUDED.stored_at
				""")
			.param("accountId", accountId)
			.param("keyVersion", sealed.keyVersion())
			.param("nonce", sealed.nonce())
			.param("ciphertext", sealed.ciphertext())
			.param("at", Timestamp.from(at))
			.update();
	}

	Optional<TokenCipher.Sealed> customerToken(UUID accountId) {
		return this.jdbc.sql("SELECT key_version, nonce, ciphertext FROM identity.customer_token WHERE account_id = :id")
			.param("id", accountId)
			.query((rs, n) -> new TokenCipher.Sealed(rs.getInt("key_version"), rs.getBytes("nonce"),
					rs.getBytes("ciphertext")))
			.optional();
	}

	private static Account row(ResultSet rs, int number) throws SQLException {
		String status = rs.getString("mentor_approval_status");
		return new Account(rs.getObject("id", UUID.class), (Long) rs.getObject("github_user_id"),
				rs.getString("github_login"), rs.getString("github_avatar_url"), Role.valueOf(rs.getString("role")),
				status == null ? null : MentorApprovalStatus.valueOf(status), instant(rs, "locked_at"),
				rs.getString("lock_reason"), instant(rs, "erased_at"), instant(rs, "created_at"));
	}

	private static @Nullable Instant instant(ResultSet rs, String column) throws SQLException {
		Timestamp value = rs.getTimestamp(column);
		return value == null ? null : value.toInstant();
	}

}
