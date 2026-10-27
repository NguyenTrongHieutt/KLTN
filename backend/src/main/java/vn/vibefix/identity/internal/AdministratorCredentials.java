package vn.vibefix.identity.internal;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * {@code identity.administrator_credential} (FR-2, AD-19, AD-22): an Administrator's email, display
 * name, Argon2 password hash, and sealed TOTP secret. The secret is opened only to check a code and
 * never leaves this module.
 */
@Component
class AdministratorCredentials {

	/** The associated data that binds a sealed TOTP secret to its account. */
	static String totpAssociatedData(UUID accountId) {
		return "totp:" + accountId;
	}

	record Credential(UUID accountId, String email, String displayName, String passwordHash,
			TokenCipher.Sealed totpSecret) {
	}

	private final JdbcClient jdbc;

	AdministratorCredentials(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	void insert(UUID accountId, String email, String displayName, String passwordHash, TokenCipher.Sealed secret,
			Instant at) {
		this.jdbc.sql("""
				INSERT INTO identity.administrator_credential (account_id, email, display_name, password_hash,
				    totp_key_version, totp_nonce, totp_ciphertext, created_at)
				VALUES (:id, :email, :name, :hash, :version, :nonce, :ciphertext, :at)
				""")
			.param("id", accountId)
			.param("email", email)
			.param("name", displayName)
			.param("hash", passwordHash)
			.param("version", secret.keyVersion())
			.param("nonce", secret.nonce())
			.param("ciphertext", secret.ciphertext())
			.param("at", Timestamp.from(at))
			.update();
	}

	Optional<Credential> findByEmail(String email) {
		return this.jdbc.sql("SELECT * FROM identity.administrator_credential WHERE email = :email")
			.param("email", email)
			.query((rs, n) -> new Credential(rs.getObject("account_id", UUID.class), rs.getString("email"),
					rs.getString("display_name"), rs.getString("password_hash"),
					new TokenCipher.Sealed(rs.getInt("totp_key_version"), rs.getBytes("totp_nonce"),
							rs.getBytes("totp_ciphertext"))))
			.optional();
	}

	Optional<Credential> findByAccount(UUID accountId) {
		return this.jdbc.sql("SELECT * FROM identity.administrator_credential WHERE account_id = :id")
			.param("id", accountId)
			.query((rs, n) -> new Credential(rs.getObject("account_id", UUID.class), rs.getString("email"),
					rs.getString("display_name"), rs.getString("password_hash"),
					new TokenCipher.Sealed(rs.getInt("totp_key_version"), rs.getBytes("totp_nonce"),
							rs.getBytes("totp_ciphertext"))))
			.optional();
	}

	/**
	 * Whether an Administrator has this email, or an enrolment for it is still open: an email names
	 * one Administrator. An enrolment that lapsed unfinished does not count; creating it again renews it.
	 */
	boolean emailInUse(String email, Instant now) {
		return this.jdbc.sql("""
				SELECT (SELECT count(*) FROM identity.administrator_credential WHERE email = :email)
				     + (SELECT count(*) FROM identity.administrator_enrolment
				        WHERE email = :email AND completed_at IS NULL AND expires_at > :now)
				""").param("email", email).param("now", Timestamp.from(now)).query(Long.class).single() > 0;
	}

	/** An Administrator as people see them: the sign-in email and the display name. */
	record Identity(String email, String displayName) {
	}

	Optional<Identity> identity(UUID accountId) {
		return this.jdbc.sql("SELECT email, display_name FROM identity.administrator_credential WHERE account_id = :id")
			.param("id", accountId)
			.query((rs, n) -> new Identity(rs.getString("email"), rs.getString("display_name")))
			.optional();
	}

	/**
	 * Accepts {@code step} if it is later than every step accepted before; false for a replay or an
	 * older step, also when two sign-ins race for the same step.
	 */
	boolean acceptStep(UUID accountId, long step) {
		return this.jdbc.sql("""
				UPDATE identity.administrator_credential SET last_totp_step = :step
				WHERE account_id = :id AND (last_totp_step IS NULL OR last_totp_step < :step)
				""").param("id", accountId).param("step", step).update() == 1;
	}

	boolean anyExists() {
		return this.jdbc.sql("SELECT count(*) FROM identity.account WHERE kind = 'ADMINISTRATOR'")
			.query(Long.class)
			.single() > 0;
	}

	/** Every active Administrator except {@code excluded}, for alerts (AD-34). */
	List<UUID> activeAdministratorsExcept(UUID excluded) {
		return this.jdbc.sql("""
				SELECT id FROM identity.account
				WHERE kind = 'ADMINISTRATOR' AND locked_at IS NULL AND erased_at IS NULL AND id <> :excluded
				ORDER BY id
				""").param("excluded", excluded).query(UUID.class).list();
	}

}
