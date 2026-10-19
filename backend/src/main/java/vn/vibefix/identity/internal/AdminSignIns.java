package vn.vibefix.identity.internal;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import vn.vibefix.audit.AuditActor;
import vn.vibefix.audit.AuditEntry;
import vn.vibefix.audit.AuditLog;
import vn.vibefix.audit.AuditObject;
import vn.vibefix.identity.internal.AdministratorCredentials.Credential;
import vn.vibefix.notification.Audience;
import vn.vibefix.notification.NotificationRequest;
import vn.vibefix.notification.Notifications;
import vn.vibefix.notification.Recipient;
import vn.vibefix.shared.ApiException;
import vn.vibefix.shared.ErrorCode;
import vn.vibefix.shared.IdGenerator;
import vn.vibefix.shared.RateLimitedException;

/**
 * The Administrator sign-in (FR-2, AD-19, AD-34): an email, an Argon2 password, and a TOTP code whose
 * step is used once. Every wrong part answers the same refusal after the same work, so an answer
 * never tells which part was wrong or whether the email is an Administrator's. Failures count per
 * email: the 5th within 15 minutes locks it for 15 minutes and alerts the other Administrators.
 * <p>
 * A failure must be counted although the sign-in is refused, so the outcome is returned and the
 * caller throws after this transaction commits.
 */
@Component
class AdminSignIns {

	static final int MAX_FAILURES = 5;

	static final Duration WINDOW = Duration.ofMinutes(15);

	static final Duration LOCK = Duration.ofMinutes(15);

	static final String SIGN_IN_LOCKED = "identity.admin_sign_in_locked";

	/** SG-11: Spring Security's Argon2id defaults (BouncyCastle). */
	static final PasswordEncoder PASSWORDS = Argon2PasswordEncoder.defaultsForSpringSecurity_v5_8();

	/** Checked against when the email has no credential, so an unknown email takes as long. */
	private static final String UNKNOWN_EMAIL_HASH = PASSWORDS.encode("vibefix: no Administrator has this email");

	sealed interface Outcome {

		record SignedIn(UUID accountId) implements Outcome {
		}

		record Refused(ApiException refusal) implements Outcome {
		}

	}

	private final JdbcClient jdbc;

	private final AdministratorCredentials credentials;

	private final Accounts accounts;

	private final TokenCipher cipher;

	private final Notifications notifications;

	private final AuditLog auditLog;

	private final IdGenerator ids;

	private final Clock clock;

	AdminSignIns(JdbcClient jdbc, AdministratorCredentials credentials, Accounts accounts, TokenCipher cipher,
			Notifications notifications, AuditLog auditLog, IdGenerator ids, Clock clock) {
		this.jdbc = jdbc;
		this.credentials = credentials;
		this.accounts = accounts;
		this.cipher = cipher;
		this.notifications = notifications;
		this.auditLog = auditLog;
		this.ids = ids;
		this.clock = clock;
	}

	@Transactional
	Outcome signIn(String rawEmail, String password, String code) {
		String email = StudentVerifications.normalize(rawEmail);
		String emailHash = StudentVerifications.sha256(email);
		Instant now = this.clock.instant();
		this.jdbc.sql("SELECT pg_advisory_xact_lock(hashtextextended(:key, 0))")
			.param("key", "identity.admin_sign_in:" + emailHash)
			.query((rs, n) -> 1)
			.single();
		Optional<Instant> lockedUntil = this.jdbc
			.sql("SELECT locked_until FROM identity.admin_sign_in_lock WHERE email_hash = :hash AND locked_until > :now")
			.param("hash", emailHash)
			.param("now", Timestamp.from(now))
			.query(Timestamp.class)
			.optional()
			.map(Timestamp::toInstant);
		if (lockedUntil.isPresent()) {
			return new Outcome.Refused(locked(Duration.between(now, lockedUntil.get())));
		}
		Optional<Credential> credential = this.credentials.findByEmail(email);
		boolean passwordMatches = PASSWORDS.matches(password,
				credential.map(Credential::passwordHash).orElse(UNKNOWN_EMAIL_HASH)) && credential.isPresent();
		if (passwordMatches && active(credential.get()) && codeAccepted(credential.get(), code, now)) {
			this.jdbc.sql("DELETE FROM identity.admin_sign_in_failure WHERE email_hash = :hash")
				.param("hash", emailHash)
				.update();
			UUID account = credential.get().accountId();
			this.auditLog.record(AuditEntry.of(AuditActor.user(account), "identity.admin_signed_in",
					AuditObject.of("identity.account", account)));
			return new Outcome.SignedIn(account);
		}
		return failed(emailHash, credential, now);
	}

	private boolean active(Credential credential) {
		return this.accounts.findById(credential.accountId()).filter(Account::isActive).isPresent();
	}

	private boolean codeAccepted(Credential credential, String code, Instant now) {
		byte[] secret = Totp.fromBase32(this.cipher.open(credential.totpSecret(),
				AdministratorCredentials.totpAssociatedData(credential.accountId())));
		OptionalLong step = Totp.matchingStep(secret, code, now);
		return step.isPresent() && this.credentials.acceptStep(credential.accountId(), step.getAsLong());
	}

	private Outcome failed(String emailHash, Optional<Credential> credential, Instant now) {
		this.jdbc.sql("INSERT INTO identity.admin_sign_in_failure (id, email_hash, failed_at) VALUES (:id, :hash, :now)")
			.param("id", this.ids.newId())
			.param("hash", emailHash)
			.param("now", Timestamp.from(now))
			.update();
		credential.ifPresent(found -> this.auditLog.record(AuditEntry.of(AuditActor.system(),
				"identity.admin_sign_in_failed", AuditObject.of("identity.account", found.accountId()))));
		long failures = this.jdbc.sql("""
				SELECT count(*) FROM identity.admin_sign_in_failure WHERE email_hash = :hash AND failed_at > :since
				""").param("hash", emailHash).param("since", Timestamp.from(now.minus(WINDOW))).query(Long.class).single();
		if (failures < MAX_FAILURES) {
			return new Outcome.Refused(new ApiException(ErrorCode.GUARD_FAILED, IdentityReason.ADMIN_SIGN_IN_FAILED));
		}
		this.jdbc.sql("""
				INSERT INTO identity.admin_sign_in_lock (email_hash, locked_until) VALUES (:hash, :until)
				ON CONFLICT (email_hash) DO UPDATE SET locked_until = :until
				""").param("hash", emailHash).param("until", Timestamp.from(now.plus(LOCK))).update();
		this.jdbc.sql("DELETE FROM identity.admin_sign_in_failure WHERE email_hash = :hash")
			.param("hash", emailHash)
			.update();
		credential.ifPresent(found -> alertOthers(found, now));
		return new Outcome.Refused(locked(LOCK));
	}

	/** {@code identity.admin_sign_in_locked} to every other active Administrator (AD-34). */
	private void alertOthers(Credential locked, Instant now) {
		List<Recipient> others = this.credentials.activeAdministratorsExcept(locked.accountId())
			.stream()
			.map(account -> new Recipient(account, Audience.ADMIN))
			.toList();
		this.auditLog.record(AuditEntry.of(AuditActor.system(), "identity.admin_sign_in_locked",
				AuditObject.of("identity.account", locked.accountId())));
		if (others.isEmpty()) {
			return;
		}
		this.notifications.send(new NotificationRequest(SIGN_IN_LOCKED, this.ids.newId(), others,
				Map.of("email", locked.email()), "/quan-tri", null, null));
	}

	private static RateLimitedException locked(Duration retryAfter) {
		return new RateLimitedException(retryAfter, IdentityReason.ADMIN_SIGN_IN_LOCKED);
	}

	/** Deletes failures that no longer count and locks that have ended; @return how many rows */
	@Transactional
	int purge() {
		Instant now = this.clock.instant();
		int failures = this.jdbc.sql("DELETE FROM identity.admin_sign_in_failure WHERE failed_at <= :since")
			.param("since", Timestamp.from(now.minus(WINDOW)))
			.update();
		int locks = this.jdbc.sql("DELETE FROM identity.admin_sign_in_lock WHERE locked_until <= :now")
			.param("now", Timestamp.from(now))
			.update();
		return failures + locks;
	}

}
