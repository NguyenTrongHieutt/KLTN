package vn.vibefix.identity.internal;

import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;

import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import vn.vibefix.audit.AuditActor;
import vn.vibefix.audit.AuditEntry;
import vn.vibefix.audit.AuditLog;
import vn.vibefix.audit.AuditObject;
import vn.vibefix.identity.Administrators;
import vn.vibefix.notification.Audience;
import vn.vibefix.notification.EmailClass;
import vn.vibefix.notification.EmailRequest;
import vn.vibefix.notification.Emails;
import vn.vibefix.notification.NotificationRequest;
import vn.vibefix.notification.Notifications;
import vn.vibefix.notification.Recipient;
import vn.vibefix.shared.ApiException;
import vn.vibefix.shared.ErrorCode;
import vn.vibefix.shared.IdGenerator;
import vn.vibefix.shared.Role;

/**
 * Administrators created by Administrators (FR-2, SG-10). Creation needs a step-up, alerts every
 * Administrator, and emails the new one a single-use link, valid for 24 hours, to set a password and
 * an authenticator app; the token is kept only as its hash. An enrolment that lapsed unfinished is
 * renewed when its email is created again.
 */
@Component
class AdministratorEnrolments implements Administrators {

	static final Duration VALID = Duration.ofHours(24);

	static final String ADMINISTRATOR_CREATED = "identity.administrator_created";

	static final String ENROLMENT_EMAIL = "identity.administrator_enrolment";

	static final String LINK_PREFIX = "/quan-tri/kich-hoat/";

	/** What an enrolment link shows: whose account it is, and the authenticator secret. */
	record EnrolmentView(String email, String displayName, String secret, String otpauthUri) {
	}

	/**
	 * One Administrator on ADM-11.
	 * @param status {@code ACTIVE}, {@code ENROLLING} while the link is open, {@code EXPIRED} once it lapsed
	 * @param lockedUntil when a sign-in lock ends, while there is one
	 */
	record AdministratorRow(UUID id, String email, String displayName, String status, Instant createdAt,
			@Nullable Instant signedInAt, @Nullable Instant lockedUntil) {
	}

	sealed interface Outcome {

		record Done(UUID accountId) implements Outcome {
		}

		record Refused(ApiException refusal, boolean endSession) implements Outcome {
		}

	}

	private record Enrolment(UUID id, UUID accountId, String email, String displayName,
			TokenCipher.@Nullable Sealed secret) {
	}

	private final JdbcClient jdbc;

	private final Accounts accounts;

	private final AdministratorCredentials credentials;

	private final StepUps stepUps;

	private final TokenCipher cipher;

	private final Emails emails;

	private final Notifications notifications;

	private final AuditLog auditLog;

	private final IdGenerator ids;

	private final Clock clock;

	private final SecureRandom random = new SecureRandom();

	AdministratorEnrolments(JdbcClient jdbc, Accounts accounts, AdministratorCredentials credentials, StepUps stepUps,
			TokenCipher cipher, Emails emails, Notifications notifications, AuditLog auditLog, IdGenerator ids,
			Clock clock) {
		this.jdbc = jdbc;
		this.accounts = accounts;
		this.credentials = credentials;
		this.stepUps = stepUps;
		this.cipher = cipher;
		this.emails = emails;
		this.notifications = notifications;
		this.auditLog = auditLog;
		this.ids = ids;
		this.clock = clock;
	}

	@Override
	public long activeCount() {
		return this.jdbc.sql("""
				SELECT count(*) FROM identity.account a JOIN identity.administrator_credential c ON c.account_id = a.id
				WHERE a.role = 'ADMINISTRATOR' AND a.locked_at IS NULL AND a.erased_at IS NULL
				""").query(Long.class).single();
	}

	@Override
	public List<UUID> activeIds() {
		return this.jdbc.sql("""
				SELECT a.id FROM identity.account a JOIN identity.administrator_credential c ON c.account_id = a.id
				WHERE a.role = 'ADMINISTRATOR' AND a.locked_at IS NULL AND a.erased_at IS NULL ORDER BY a.id
				""").query(UUID.class).list();
	}

	/** ADM-11's list, oldest first. */
	@Transactional(readOnly = true)
	List<AdministratorRow> list() {
		Instant now = this.clock.instant();
		return this.jdbc.sql("""
				SELECT a.id, a.created_at, a.signed_in_at,
				       COALESCE(c.email, e.email) AS email, COALESCE(c.display_name, e.display_name) AS display_name,
				       CASE WHEN c.account_id IS NOT NULL THEN 'ACTIVE'
				            WHEN e.expires_at > :now THEN 'ENROLLING' ELSE 'EXPIRED' END AS status
				FROM identity.account a
				LEFT JOIN identity.administrator_credential c ON c.account_id = a.id
				LEFT JOIN identity.administrator_enrolment e ON e.account_id = a.id
				WHERE a.role = 'ADMINISTRATOR' AND a.erased_at IS NULL
				ORDER BY a.created_at, a.id
				""").param("now", Timestamp.from(now)).query((rs, n) -> {
			String email = rs.getString("email");
			return new AdministratorRow(rs.getObject("id", UUID.class), email, rs.getString("display_name"),
					rs.getString("status"), rs.getTimestamp("created_at").toInstant(),
					rs.getTimestamp("signed_in_at") == null ? null : rs.getTimestamp("signed_in_at").toInstant(),
					email == null ? null : lockedUntil(StudentVerifications.sha256(email), now));
		}).list();
	}

	/**
	 * Creates an Administrator after the creator's step-up, or renews a lapsed enrolment of the email.
	 * A refusal is returned so that a failed step-up stays counted.
	 */
	@Transactional
	Outcome create(UUID creator, String rawEmail, String rawName, String stepUpCode) {
		StepUps.Outcome stepUp = this.stepUps.check(creator, stepUpCode);
		if (stepUp instanceof StepUps.Outcome.Refused refused) {
			return new Outcome.Refused(refused.refusal(), refused.endSession());
		}
		String email = StudentVerifications.normalize(rawEmail);
		String name = rawName.strip();
		Instant now = this.clock.instant();
		this.jdbc.sql("SELECT pg_advisory_xact_lock(hashtextextended(:key, 0))")
			.param("key", "identity.administrator_email:" + email)
			.query((rs, n) -> 1)
			.single();
		if (this.credentials.emailInUse(email, now)) {
			return new Outcome.Refused(new ApiException(ErrorCode.CONFLICT, IdentityReason.ADMINISTRATOR_EMAIL_IN_USE),
					false);
		}
		String token = HexFormat.of().formatHex(bytes(16));
		String tokenHash = StudentVerifications.sha256(token);
		Optional<UUID> lapsed = this.jdbc.sql("SELECT account_id FROM identity.administrator_enrolment WHERE email = :email")
			.param("email", email)
			.query(UUID.class)
			.optional();
		UUID account;
		if (lapsed.isPresent()) {
			account = lapsed.get();
			this.jdbc.sql("""
					UPDATE identity.administrator_enrolment
					SET display_name = :name, token_hash = :hash, totp_key_version = NULL, totp_nonce = NULL,
					    totp_ciphertext = NULL, created_by = :creator, created_at = :now, expires_at = :expires
					WHERE account_id = :account
					""")
				.param("account", account)
				.param("name", name)
				.param("hash", tokenHash)
				.param("creator", creator)
				.param("now", Timestamp.from(now))
				.param("expires", Timestamp.from(now.plus(VALID)))
				.update();
		}
		else {
			account = this.ids.newId();
			this.accounts.insert(new Account(account, null, null, null, Role.ADMINISTRATOR, null, null, null, null, now));
			this.jdbc.sql("""
					INSERT INTO identity.administrator_enrolment (id, account_id, email, display_name, token_hash,
					    created_by, created_at, expires_at)
					VALUES (:id, :account, :email, :name, :hash, :creator, :now, :expires)
					""")
				.param("id", this.ids.newId())
				.param("account", account)
				.param("email", email)
				.param("name", name)
				.param("hash", tokenHash)
				.param("creator", creator)
				.param("now", Timestamp.from(now))
				.param("expires", Timestamp.from(now.plus(VALID)))
				.update();
		}
		String by = this.credentials.identity(creator).map(AdministratorCredentials.Identity::displayName).orElse("");
		this.emails.send(EmailRequest.withPrivateLink(ENROLMENT_EMAIL, this.ids.newId(), account, email,
				EmailClass.TRANSACTIONAL, Map.of("by", by), LINK_PREFIX + token));
		this.auditLog.record(AuditEntry.of(AuditActor.user(creator), ADMINISTRATOR_CREATED,
				AuditObject.of("identity.account", account)));
		List<Recipient> everyone = this.jdbc.sql("""
				SELECT a.id FROM identity.account a JOIN identity.administrator_credential c ON c.account_id = a.id
				WHERE a.role = 'ADMINISTRATOR' AND a.locked_at IS NULL AND a.erased_at IS NULL ORDER BY a.id
				""").query(UUID.class).list().stream().map(id -> new Recipient(id, Audience.ADMIN)).toList();
		this.notifications.send(new NotificationRequest(ADMINISTRATOR_CREATED, this.ids.newId(), everyone,
				Map.of("email", email), "/quan-tri/quan-tri-vien", null, null));
		return new Outcome.Done(account);
	}

	/** The enrolment page: the account and its authenticator secret, created on the first read. */
	@Transactional
	Optional<EnrolmentView> view(String token) {
		return open(token).map(enrolment -> {
			String secret = enrolment.secret() == null ? newSecret(enrolment)
					: this.cipher.open(enrolment.secret(), AdministratorCredentials.totpAssociatedData(enrolment.accountId()));
			return new EnrolmentView(enrolment.email(), enrolment.displayName(), secret,
					Totp.uri(enrolment.email(), Totp.fromBase32(secret)));
		});
	}

	/**
	 * Sets the password and enrols the authenticator: the code must come from the secret the page
	 * showed. The token is then used up, and the new Administrator signed in by the caller.
	 */
	@Transactional
	Outcome complete(String token, char[] password, String code) {
		Optional<Enrolment> found = open(token);
		if (found.isEmpty() || found.get().secret() == null) {
			return new Outcome.Refused(new ApiException(ErrorCode.NOT_FOUND), false);
		}
		Enrolment enrolment = found.get();
		Instant now = this.clock.instant();
		String secret = this.cipher.open(enrolment.secret(), AdministratorCredentials.totpAssociatedData(enrolment.accountId()));
		OptionalLong step = Totp.matchingStep(Totp.fromBase32(secret), code, now);
		if (step.isEmpty()) {
			return new Outcome.Refused(new ApiException(ErrorCode.GUARD_FAILED, IdentityReason.ENROLMENT_CODE_WRONG), false);
		}
		this.credentials.insert(enrolment.accountId(), enrolment.email(), enrolment.displayName(),
				AdminSignIns.PASSWORDS.encode(new String(password)), enrolment.secret(), now);
		this.credentials.acceptStep(enrolment.accountId(), step.getAsLong());
		this.jdbc.sql("UPDATE identity.administrator_enrolment SET completed_at = :now WHERE id = :id")
			.param("id", enrolment.id())
			.param("now", Timestamp.from(now))
			.update();
		this.jdbc.sql("UPDATE identity.account SET signed_in_at = :now WHERE id = :id")
			.param("id", enrolment.accountId())
			.param("now", Timestamp.from(now))
			.update();
		this.auditLog.record(AuditEntry.of(AuditActor.user(enrolment.accountId()), "identity.administrator_enrolled",
				AuditObject.of("identity.account", enrolment.accountId())));
		return new Outcome.Done(enrolment.accountId());
	}

	/** The open enrolment of a token: unknown, used, and lapsed ones look the same, absent. */
	private Optional<Enrolment> open(String token) {
		if (token == null || !token.matches("[0-9a-f]{32}")) {
			return Optional.empty();
		}
		return this.jdbc.sql("""
				SELECT id, account_id, email, display_name, totp_key_version, totp_nonce, totp_ciphertext
				FROM identity.administrator_enrolment
				WHERE token_hash = :hash AND completed_at IS NULL AND expires_at > :now
				FOR UPDATE
				""")
			.param("hash", StudentVerifications.sha256(token))
			.param("now", Timestamp.from(this.clock.instant()))
			.query((rs, n) -> new Enrolment(rs.getObject("id", UUID.class), rs.getObject("account_id", UUID.class),
					rs.getString("email"), rs.getString("display_name"),
					rs.getBytes("totp_ciphertext") == null ? null
							: new TokenCipher.Sealed(rs.getInt("totp_key_version"), rs.getBytes("totp_nonce"),
									rs.getBytes("totp_ciphertext"))))
			.optional();
	}

	private String newSecret(Enrolment enrolment) {
		String secret = Totp.base32(Totp.newSecret());
		TokenCipher.Sealed sealed = this.cipher.seal(secret, AdministratorCredentials.totpAssociatedData(enrolment.accountId()));
		this.jdbc.sql("""
				UPDATE identity.administrator_enrolment
				SET totp_key_version = :version, totp_nonce = :nonce, totp_ciphertext = :ciphertext WHERE id = :id
				""")
			.param("id", enrolment.id())
			.param("version", sealed.keyVersion())
			.param("nonce", sealed.nonce())
			.param("ciphertext", sealed.ciphertext())
			.update();
		return secret;
	}

	private @Nullable Instant lockedUntil(String emailHash, Instant now) {
		return this.jdbc
			.sql("SELECT locked_until FROM identity.admin_sign_in_lock WHERE email_hash = :hash AND locked_until > :now")
			.param("hash", emailHash)
			.param("now", Timestamp.from(now))
			.query(Timestamp.class)
			.optional()
			.map(Timestamp::toInstant)
			.orElse(null);
	}

	private byte[] bytes(int length) {
		byte[] bytes = new byte[length];
		this.random.nextBytes(bytes);
		return bytes;
	}

}
