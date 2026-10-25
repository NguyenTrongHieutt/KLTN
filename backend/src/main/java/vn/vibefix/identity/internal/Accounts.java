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

	/** The account Google's subject signs into (AD-19); never found by email. */
	Optional<Account> findByGoogleSub(String googleSub) {
		return this.jdbc.sql("SELECT * FROM identity.account WHERE google_sub = :sub AND kind = 'USER'")
			.param("sub", googleSub)
			.query(Accounts::row)
			.optional();
	}

	/** An account with a role: an Administrator's, or a test's account from before Google sign-in. */
	void insert(Account account) {
		this.jdbc.sql("""
				INSERT INTO identity.account
				    (id, kind, github_user_id, github_login, github_avatar_url, role, mentor_approval_status, created_at,
				     signed_in_at)
				VALUES (:id, :kind, :githubUserId, :githubLogin, :githubAvatarUrl, :role, :mentorApprovalStatus, :createdAt,
				        :createdAt)
				""")
			.param("id", account.id())
			.param("kind", account.kind().name())
			.param("githubUserId", account.githubUserId())
			.param("githubLogin", account.githubLogin())
			.param("githubAvatarUrl", account.githubAvatarUrl())
			.param("role", account.role() == null ? null : account.role().name())
			.param("mentorApprovalStatus",
					account.mentorApprovalStatus() == null ? null : account.mentorApprovalStatus().name())
			.param("createdAt", Timestamp.from(account.createdAt()))
			.update();
	}

	/**
	 * A first Google sign-in's account (FR-1): a {@code USER} with no role, keyed by the subject. When
	 * another sign-in of the same subject created it first, nothing changes.
	 * @return whether this call created it
	 */
	boolean insertGoogleUser(UUID id, String googleSub, String googleEmail, @Nullable String googleName, Instant at) {
		return this.jdbc.sql("""
				INSERT INTO identity.account (id, kind, google_sub, google_email, google_name, created_at, signed_in_at)
				VALUES (:id, 'USER', :sub, :email, :name, :at, :at)
				ON CONFLICT (google_sub) DO NOTHING
				""")
			.param("id", id)
			.param("sub", googleSub)
			.param("email", googleEmail)
			.param("name", googleName)
			.param("at", Timestamp.from(at))
			.update() == 1;
	}

	/** A Google sign-in reads the verified email and the name again (FR-1, FR-48): either may have changed. */
	void recordGoogleSignIn(UUID id, String googleEmail, @Nullable String googleName, Instant at) {
		this.jdbc.sql("""
				UPDATE identity.account SET google_email = :email, google_name = :name, signed_in_at = :at
				WHERE id = :id
				""")
			.param("id", id)
			.param("email", googleEmail)
			.param("name", googleName)
			.param("at", Timestamp.from(at))
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

	/** The GitHub primary verified email, where notifications went before Google sign-in (FR-48). */
	void recordGitHubEmail(UUID id, String email) {
		this.jdbc.sql("UPDATE identity.account SET github_email = :email WHERE id = :id")
			.param("id", id)
			.param("email", email)
			.update();
	}

	/** The GitHub primary verified email on file, if any. */
	Optional<String> gitHubEmail(UUID id) {
		return this.jdbc.sql("SELECT github_email FROM identity.account WHERE id = :id")
			.param("id", id)
			.query((row, number) -> Optional.ofNullable(row.getString("github_email")))
			.optional()
			.flatMap(address -> address);
	}

	/**
	 * Where the account's notifications go (FR-48): the verified Google email, else the GitHub one;
	 * none once it is erased or while no address is known. A saved contact email comes first in
	 * Story 19.3.
	 */
	Optional<String> notificationAddress(UUID id) {
		return this.jdbc.sql("""
				SELECT coalesce(google_email, github_email) AS address FROM identity.account
				WHERE id = :id AND erased_at IS NULL
				""")
			.param("id", id)
			.query((row, number) -> Optional.ofNullable(row.getString("address")))
			.optional()
			.flatMap(address -> address);
	}

	/**
	 * Records that the account accepted the notice's version; a version accepted before keeps its first time.
	 * @return whether this call recorded it
	 */
	boolean insertAcknowledgment(UUID id, UUID accountId, String notice, String noticeVersion, Instant at) {
		return this.jdbc.sql("""
				INSERT INTO identity.acknowledgment (id, account_id, notice, notice_version, acknowledged_at)
				VALUES (:id, :accountId, :notice, :version, :at)
				ON CONFLICT (account_id, notice, notice_version) DO NOTHING
				""")
			.param("id", id)
			.param("accountId", accountId)
			.param("notice", notice)
			.param("version", noticeVersion)
			.param("at", Timestamp.from(at))
			.update() == 1;
	}

	/** Whether the account accepted this version of the notice (NFR-9). */
	boolean hasAcknowledged(UUID accountId, String notice, String noticeVersion) {
		return this.jdbc.sql("""
				SELECT EXISTS (SELECT 1 FROM identity.acknowledgment
				               WHERE account_id = :accountId AND notice = :notice AND notice_version = :version)
				""")
			.param("accountId", accountId)
			.param("notice", notice)
			.param("version", noticeVersion)
			.query(Boolean.class)
			.single();
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
		String role = rs.getString("role");
		String status = rs.getString("mentor_approval_status");
		return new Account(rs.getObject("id", UUID.class), Account.Kind.valueOf(rs.getString("kind")),
				(Long) rs.getObject("github_user_id"), rs.getString("github_login"), rs.getString("github_avatar_url"),
				role == null ? null : Role.valueOf(role), status == null ? null : MentorApprovalStatus.valueOf(status),
				rs.getString("google_sub"), rs.getString("google_email"), rs.getString("google_name"),
				instant(rs, "locked_at"), rs.getString("lock_reason"), instant(rs, "erased_at"), instant(rs, "created_at"));
	}

	private static @Nullable Instant instant(ResultSet rs, String column) throws SQLException {
		Timestamp value = rs.getTimestamp(column);
		return value == null ? null : value.toInstant();
	}

}
