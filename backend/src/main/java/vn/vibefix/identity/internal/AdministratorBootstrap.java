package vn.vibefix.identity.internal;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import vn.vibefix.audit.AuditActor;
import vn.vibefix.audit.AuditEntry;
import vn.vibefix.audit.AuditLog;
import vn.vibefix.audit.AuditObject;
import vn.vibefix.shared.IdGenerator;
import vn.vibefix.shared.Role;

/**
 * Creates the first Administrator (FR-2, AD-19, SG-10): an account with no GitHub identity, an
 * Argon2 password, and a sealed TOTP secret returned once for the authenticator app. It refuses once
 * any Administrator exists; later ones are created by Administrators (Story 1.12).
 */
@Component
class AdministratorBootstrap {

	static final int MIN_PASSWORD_LENGTH = 12;

	/** What the person running the bootstrap needs once: the secret, as text and as a URI. */
	record Created(UUID accountId, String secret, String otpauthUri) {
	}

	private final JdbcClient jdbc;

	private final Accounts accounts;

	private final AdministratorCredentials credentials;

	private final TokenCipher cipher;

	private final AuditLog auditLog;

	private final IdGenerator ids;

	private final Clock clock;

	AdministratorBootstrap(JdbcClient jdbc, Accounts accounts, AdministratorCredentials credentials,
			TokenCipher cipher, AuditLog auditLog, IdGenerator ids, Clock clock) {
		this.jdbc = jdbc;
		this.accounts = accounts;
		this.credentials = credentials;
		this.cipher = cipher;
		this.auditLog = auditLog;
		this.ids = ids;
		this.clock = clock;
	}

	/**
	 * @throws IllegalStateException when an Administrator exists already
	 * @throws IllegalArgumentException for an address that is not one, an empty or long name, or a
	 * password shorter than 12 characters
	 */
	@Transactional
	Created createFirst(String rawEmail, String rawName, char[] password) {
		String email = StudentVerifications.normalize(rawEmail);
		String name = rawName.strip();
		if (!email.matches("[^@\\s]+@[^@\\s]+\\.[^@\\s]+") || email.length() > 254) {
			throw new IllegalArgumentException("The email is not an address: " + rawEmail);
		}
		if (name.isEmpty() || name.length() > 100) {
			throw new IllegalArgumentException("The name needs 1 to 100 characters");
		}
		if (password.length < MIN_PASSWORD_LENGTH) {
			throw new IllegalArgumentException("The password needs at least " + MIN_PASSWORD_LENGTH + " characters");
		}
		// Two bootstraps at once: the second waits, then finds the first Administrator.
		this.jdbc.sql("SELECT pg_advisory_xact_lock(hashtextextended('identity.bootstrap', 0))")
			.query((rs, n) -> 1)
			.single();
		if (this.credentials.anyExists()) {
			throw new IllegalStateException("An Administrator exists already; Administrators create the others");
		}
		Instant now = this.clock.instant();
		UUID id = this.ids.newId();
		this.accounts.insert(new Account(id, null, null, null, Role.ADMINISTRATOR, null, null, null, now));
		byte[] secret = Totp.newSecret();
		String encoded = Totp.base32(secret);
		this.credentials.insert(id, email, name, AdminSignIns.PASSWORDS.encode(new String(password)),
				this.cipher.seal(encoded, AdministratorCredentials.totpAssociatedData(id)), now);
		this.auditLog.record(AuditEntry.of(AuditActor.system(), "identity.administrator_bootstrapped",
				AuditObject.of("identity.account", id)));
		return new Created(id, encoded, Totp.uri(email, secret));
	}

}
