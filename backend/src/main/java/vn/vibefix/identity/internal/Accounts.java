package vn.vibefix.identity.internal;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import vn.vibefix.shared.Kind;

/** Reads and writes {@code identity.account}, {@code identity.acknowledgment}, and the kept GitHub token. */
@Component
class Accounts {

	/**
	 * The account is unlocked and not erased (AD-19): what every command needs, {@code ActiveAccount}'s
	 * rule. A condition on {@code identity.account} aliased {@code a}.
	 */
	static final String ACTIVE = "a.locked_at IS NULL AND a.erased_at IS NULL";

	/**
	 * GitHub is linked (FR-80, AD-19): what new work needs on top of {@link #ACTIVE}, {@code GitHubGate}'s
	 * rule and the Mentor directory's. A condition on {@code identity.account} aliased {@code a}.
	 */
	static final String GITHUB_LINKED = "a.github_user_id IS NOT NULL";

	/**
	 * What the global gates and the capabilities read of an account: its kind, {@link #ACTIVE},
	 * {@link #GITHUB_LINKED}, and whether {@code locked_at} is set.
	 */
	record Standing(Kind kind, boolean active, boolean gitHubLinked, boolean locked) {
	}

	private final JdbcClient jdbc;

	Accounts(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	Optional<Account> findById(UUID id) {
		return this.jdbc.sql("SELECT * FROM identity.account WHERE id = :id").param("id", id).query(Accounts::row)
			.optional();
	}

	/** The account's standing under the global gates, read now (AD-19); empty for an unknown account. */
	Optional<Standing> standing(UUID id) {
		return this.jdbc.sql("SELECT a.kind, (" + ACTIVE + ") AS active, (" + GITHUB_LINKED + ") AS linked,"
				+ " (a.locked_at IS NOT NULL) AS locked FROM identity.account a WHERE a.id = :id")
			.param("id", id)
			.query((rs, n) -> new Standing(Kind.valueOf(rs.getString("kind")), rs.getBoolean("active"),
					rs.getBoolean("linked"), rs.getBoolean("locked")))
			.optional();
	}

	/** {@code identity.lockedAmong} (AD-41): the locked accounts among {@code ids}, each with its {@code locked_at}. */
	Map<UUID, Instant> lockedAmong(Collection<UUID> ids) {
		Map<UUID, Instant> locked = new HashMap<>();
		if (ids.isEmpty()) {
			return locked;
		}
		this.jdbc.sql("SELECT id, locked_at FROM identity.account WHERE id IN (:ids) AND locked_at IS NOT NULL")
			.param("ids", List.copyOf(new HashSet<>(ids)))
			.query(rs -> {
				locked.put(rs.getObject("id", UUID.class), rs.getTimestamp("locked_at").toInstant());
			});
		return locked;
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

	/** An Administrator's account ({@link Account#administrator}), with the role its row keeps writing. */
	void insert(Account account) {
		this.jdbc.sql("""
				INSERT INTO identity.account
				    (id, kind, github_user_id, github_login, github_avatar_url, role, created_at, signed_in_at)
				VALUES (:id, :kind, :githubUserId, :githubLogin, :githubAvatarUrl, :role, :createdAt, :createdAt)
				""")
			.param("id", account.id())
			.param("kind", account.kind().name())
			.param("githubUserId", account.githubUserId())
			.param("githubLogin", account.githubLogin())
			.param("githubAvatarUrl", account.githubAvatarUrl())
			.param("role", account.role())
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

	/**
	 * Links the GitHub account, or refreshes the linked one (FR-80): its numeric ID, the current login and
	 * avatar, and the time. The primary verified email replaces the one on file only when GitHub gave one.
	 * @throws org.springframework.dao.DuplicateKeyException when another account holds the ID meanwhile
	 */
	void linkGitHub(UUID id, long githubUserId, String githubLogin, @Nullable String githubAvatarUrl,
			@Nullable String githubEmail, Instant at) {
		this.jdbc.sql("""
				UPDATE identity.account
				SET github_user_id = :githubUserId, github_login = :login, github_avatar_url = :avatar,
				    github_email = coalesce(:email, github_email), github_linked_at = :at
				WHERE id = :id
				""")
			.param("id", id)
			.param("githubUserId", githubUserId)
			.param("login", githubLogin)
			.param("avatar", githubAvatarUrl)
			.param("email", githubEmail)
			.param("at", Timestamp.from(at))
			.update();
	}

	/** Removes the GitHub link and its public facts at once (FR-80); the person-link signals stay (AD-24). */
	void unlinkGitHub(UUID id) {
		this.jdbc.sql("""
				UPDATE identity.account
				SET github_user_id = NULL, github_login = NULL, github_avatar_url = NULL, github_email = NULL,
				    github_linked_at = NULL
				WHERE id = :id
				""").param("id", id).update();
	}

	/** When GitHub was last linked or re-linked, while it is linked. */
	Optional<Instant> gitHubLinkedAt(UUID id) {
		return this.jdbc.sql("SELECT github_linked_at FROM identity.account WHERE id = :id")
			.param("id", id)
			.query((row, number) -> Optional.ofNullable(instant(row, "github_linked_at")))
			.optional()
			.flatMap(at -> at);
	}

	/** Locks the account's row for a link change, so two of them never interleave. */
	Optional<Account> lockForLink(UUID id) {
		return this.jdbc.sql("SELECT * FROM identity.account WHERE id = :id FOR UPDATE")
			.param("id", id)
			.query(Accounts::row)
			.optional();
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
	 * Where the account's notifications go (FR-48): the profile's contact email once saved (Story 19.3),
	 * else the verified Google email, else the GitHub one; none once it is erased or while no address
	 * is known.
	 */
	Optional<String> notificationAddress(UUID id) {
		return this.jdbc.sql("""
				SELECT coalesce(p.contact_email, a.google_email, a.github_email) AS address
				FROM identity.account a LEFT JOIN identity.profile p ON p.account_id = a.id
				WHERE a.id = :id AND a.erased_at IS NULL
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

	/** Stores or replaces the account's sealed GitHub token (AD-22, SG-9); the table keeps its first name. */
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

	/** Deletes the account's kept GitHub token, if any. */
	void deleteCustomerToken(UUID accountId) {
		this.jdbc.sql("DELETE FROM identity.customer_token WHERE account_id = :id").param("id", accountId).update();
	}

	Optional<TokenCipher.Sealed> customerToken(UUID accountId) {
		return this.jdbc.sql("SELECT key_version, nonce, ciphertext FROM identity.customer_token WHERE account_id = :id")
			.param("id", accountId)
			.query((rs, n) -> new TokenCipher.Sealed(rs.getInt("key_version"), rs.getBytes("nonce"),
					rs.getBytes("ciphertext")))
			.optional();
	}

	private static Account row(ResultSet rs, int number) throws SQLException {
		return new Account(rs.getObject("id", UUID.class), Kind.valueOf(rs.getString("kind")),
				(Long) rs.getObject("github_user_id"), rs.getString("github_login"), rs.getString("github_avatar_url"),
				rs.getString("role"), rs.getString("google_sub"), rs.getString("google_email"),
				rs.getString("google_name"), instant(rs, "locked_at"), rs.getString("lock_reason"), instant(rs, "erased_at"),
				instant(rs, "created_at"));
	}

	private static @Nullable Instant instant(ResultSet rs, String column) throws SQLException {
		Timestamp value = rs.getTimestamp(column);
		return value == null ? null : value.toInstant();
	}

}
