package vn.vibefix.identity.internal;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.MonthDay;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import vn.vibefix.audit.AuditActor;
import vn.vibefix.audit.AuditEntry;
import vn.vibefix.audit.AuditLog;
import vn.vibefix.audit.AuditObject;
import vn.vibefix.identity.StudentVerification;
import vn.vibefix.notification.EmailClass;
import vn.vibefix.notification.EmailRequest;
import vn.vibefix.notification.Emails;
import vn.vibefix.notification.Todos;
import vn.vibefix.shared.ApiException;
import vn.vibefix.shared.ErrorCode;
import vn.vibefix.shared.IdGenerator;
import vn.vibefix.shared.RateLimitedException;
import vn.vibefix.settings.Settings;

/**
 * Student verification by a school email code (FR-5, AD-34, UX-DR19). A code is 6 digits, valid for
 * 10 minutes, with 5 attempts; it is kept only as a hash, and a new send replaces the pending one.
 * Sends are limited to 3 per account per rolling hour and 10 per address per UTC day, both counted
 * in PostgreSQL. A correct code verifies the account until the next 30 September, records the audit
 * entry, and closes the {@code identity.verify_student} to-do.
 * <p>
 * Every command locks the account row first, so one account's sends and attempts run one at a time;
 * a transaction-scoped advisory lock on the address makes "verifies at most one account" hold across
 * accounts.
 */
@Component
class StudentVerifications implements StudentVerification {

	/** The email template and notification type of the code (EXPERIENCE.md Notification Inventory). */
	static final String SCHOOL_EMAIL_CODE = "identity.school_email_code";

	static final Duration CODE_LIFETIME = Duration.ofMinutes(10);

	static final int MAX_ATTEMPTS = 5;

	static final int SENDS_PER_ACCOUNT_AND_HOUR = 3;

	static final int SENDS_PER_ADDRESS_AND_DAY = 10;

	/** The last valid day of a school year (OQ8 confirms it before production). */
	static final MonthDay RENEWAL = MonthDay.of(9, 30);

	static final ZoneId VIETNAM = ZoneId.of("Asia/Ho_Chi_Minh");

	enum Status {

		UNVERIFIED, VERIFIED, EXPIRED

	}

	/** The account's verification, if it ever verified. */
	record Verification(String method, @Nullable String schoolEmail, Instant verifiedAt, LocalDate validUntil) {
	}

	/** The code waiting for its entry; {@code expired} once its 10 minutes are over. */
	record PendingCode(String email, Instant sentAt, Instant expiresAt, int attemptsLeft, boolean expired) {
	}

	record View(Status status, @Nullable Verification verification, @Nullable PendingCode pendingCode) {
	}

	/** How a code entry ended. The attempt counts even when the entry is refused. */
	sealed interface Confirmation {

		record Verified(View view) implements Confirmation {
		}

		record Refused(ApiException refusal) implements Confirmation {
		}

	}

	private record CodeRow(UUID id, String email, String codeHash, Instant sentAt, Instant expiresAt, int attempts) {
	}

	private final JdbcClient jdbc;

	private final Settings settings;

	private final Emails emails;

	private final Todos todos;

	private final AuditLog auditLog;

	private final IdGenerator ids;

	private final Clock clock;

	private final SecureRandom random = new SecureRandom();

	StudentVerifications(JdbcClient jdbc, Settings settings, Emails emails, Todos todos, AuditLog auditLog,
			IdGenerator ids, Clock clock) {
		this.jdbc = jdbc;
		this.settings = settings;
		this.emails = emails;
		this.todos = todos;
		this.auditLog = auditLog;
		this.ids = ids;
		this.clock = clock;
	}

	@Override
	public boolean isVerified(UUID accountId) {
		return verification(accountId).filter(this::current).isPresent();
	}

	/** The account's verification status and the code waiting for its entry. */
	@Transactional(readOnly = true)
	View view(UUID accountId) {
		Optional<Verification> verification = verification(accountId);
		Status status = verification.map(found -> current(found) ? Status.VERIFIED : Status.EXPIRED)
			.orElse(Status.UNVERIFIED);
		PendingCode pending = status == Status.VERIFIED ? null : pending(accountId).map(this::pendingView).orElse(null);
		return new View(status, verification.orElse(null), pending);
	}

	/**
	 * Emails a new code to {@code address}, replacing the pending one.
	 * @throws ApiException {@code CONFLICT} when the account is verified, or with
	 * {@code ALREADY_USED_FOR_VERIFICATION}; {@code GUARD_FAILED} with {@code EMAIL_DOMAIN_NOT_ALLOWED}
	 * @throws RateLimitedException past either send limit
	 */
	@Transactional
	View send(UUID accountId, String address) {
		String email = normalize(address);
		lockAccount(accountId);
		if (isVerified(accountId)) {
			throw new ApiException(ErrorCode.CONFLICT);
		}
		if (!this.settings.allowsSchoolEmail(email)) {
			throw new ApiException(ErrorCode.GUARD_FAILED, IdentityReason.EMAIL_DOMAIN_NOT_ALLOWED);
		}
		if (usedByAnotherAccount(email, accountId)) {
			throw new ApiException(ErrorCode.CONFLICT, IdentityReason.ALREADY_USED_FOR_VERIFICATION);
		}
		Instant now = this.clock.instant();
		requireAccountLimit(accountId, now);
		requireAddressLimit(email, now);
		this.jdbc.sql("""
				UPDATE identity.school_email_code SET replaced_at = :now
				WHERE account_id = :account AND replaced_at IS NULL AND consumed_at IS NULL
				""").param("account", accountId).param("now", Timestamp.from(now)).update();
		UUID id = this.ids.newId();
		String code = "%06d".formatted(this.random.nextInt(1_000_000));
		this.jdbc.sql("""
				INSERT INTO identity.school_email_code (id, account_id, email, code_hash, sent_at, expires_at)
				VALUES (:id, :account, :email, :hash, :now, :expires)
				""")
			.param("id", id)
			.param("account", accountId)
			.param("email", email)
			.param("hash", hash(id, code))
			.param("now", Timestamp.from(now))
			.param("expires", Timestamp.from(now.plus(CODE_LIFETIME)))
			.update();
		this.emails.send(new EmailRequest(SCHOOL_EMAIL_CODE, id, accountId, email, EmailClass.ONE_TIME_CODE,
				Map.of("code", code)));
		return view(accountId);
	}

	/**
	 * Checks {@code code} against the pending code. A wrong entry still counts, so the outcome is
	 * returned rather than thrown: the caller refuses after this transaction commits.
	 */
	@Transactional
	Confirmation confirm(UUID accountId, String code) {
		lockAccount(accountId);
		if (isVerified(accountId)) {
			return refused(new ApiException(ErrorCode.CONFLICT));
		}
		Optional<CodeRow> pending = pending(accountId);
		if (pending.isEmpty()) {
			return refused(new ApiException(ErrorCode.NOT_FOUND));
		}
		CodeRow row = pending.get();
		Instant now = this.clock.instant();
		if (row.attempts() >= MAX_ATTEMPTS) {
			return refused(new ApiException(ErrorCode.GUARD_FAILED, IdentityReason.TOO_MANY_ATTEMPTS));
		}
		if (!now.isBefore(row.expiresAt())) {
			return refused(new ApiException(ErrorCode.GUARD_FAILED, IdentityReason.CODE_EXPIRED));
		}
		List<Integer> counted = this.jdbc.sql("""
				UPDATE identity.school_email_code SET attempts = attempts + 1
				WHERE id = :id AND attempts < :max RETURNING attempts
				""").param("id", row.id()).param("max", MAX_ATTEMPTS).query(Integer.class).list();
		if (counted.isEmpty()) {
			return refused(new ApiException(ErrorCode.GUARD_FAILED, IdentityReason.TOO_MANY_ATTEMPTS));
		}
		if (!MessageDigest.isEqual(hash(row.id(), code).getBytes(StandardCharsets.US_ASCII),
				row.codeHash().getBytes(StandardCharsets.US_ASCII))) {
			return refused(new ApiException(ErrorCode.GUARD_FAILED, IdentityReason.WRONG_CODE));
		}
		this.jdbc.sql("UPDATE identity.school_email_code SET consumed_at = :now WHERE id = :id")
			.param("id", row.id())
			.param("now", Timestamp.from(now))
			.update();
		if (usedByAnotherAccount(row.email(), accountId)) {
			return refused(new ApiException(ErrorCode.CONFLICT, IdentityReason.ALREADY_USED_FOR_VERIFICATION));
		}
		LocalDate validUntil = validUntil(LocalDate.ofInstant(now, VIETNAM));
		this.jdbc.sql("""
				INSERT INTO identity.student_verification (account_id, method, school_email, verified_at, valid_until)
				VALUES (:account, 'SCHOOL_EMAIL', :email, :now, :until)
				ON CONFLICT (account_id) DO UPDATE
				SET method = 'SCHOOL_EMAIL', school_email = :email, verified_at = :now, valid_until = :until
				""")
			.param("account", accountId)
			.param("email", row.email())
			.param("now", Timestamp.from(now))
			.param("until", validUntil)
			.update();
		this.auditLog.record(AuditEntry.of(AuditActor.user(accountId), "identity.student_verified",
				AuditObject.of("identity.account", accountId), AuditObject.of("identity.school_email_code", row.id())));
		this.todos.close(SignIns.VERIFY_STUDENT, accountId, accountId);
		return new Confirmation.Verified(view(accountId));
	}

	/** The first 30 September after {@code today}: a school year's last day (FR-5). */
	static LocalDate validUntil(LocalDate today) {
		LocalDate thisYear = RENEWAL.atYear(today.getYear());
		return today.isBefore(thisYear) ? thisYear : RENEWAL.atYear(today.getYear() + 1);
	}

	/** An address as stored and compared: without surrounding spaces, in lower case. */
	static String normalize(String address) {
		return address.strip().toLowerCase(Locale.ROOT);
	}

	private boolean current(Verification verification) {
		return !LocalDate.ofInstant(this.clock.instant(), VIETNAM).isAfter(verification.validUntil());
	}

	private Optional<Verification> verification(UUID accountId) {
		return this.jdbc.sql("""
				SELECT method, school_email, verified_at, valid_until FROM identity.student_verification
				WHERE account_id = :account
				""")
			.param("account", accountId)
			.query((rs, n) -> new Verification(rs.getString("method"), rs.getString("school_email"),
					rs.getTimestamp("verified_at").toInstant(), rs.getObject("valid_until", LocalDate.class)))
			.optional();
	}

	private Optional<CodeRow> pending(UUID accountId) {
		return this.jdbc.sql("""
				SELECT id, email, code_hash, sent_at, expires_at, attempts FROM identity.school_email_code
				WHERE account_id = :account AND replaced_at IS NULL AND consumed_at IS NULL
				ORDER BY sent_at DESC LIMIT 1
				""")
			.param("account", accountId)
			.query((rs, n) -> new CodeRow(rs.getObject("id", UUID.class), rs.getString("email"),
					rs.getString("code_hash"), rs.getTimestamp("sent_at").toInstant(),
					rs.getTimestamp("expires_at").toInstant(), rs.getInt("attempts")))
			.optional();
	}

	private PendingCode pendingView(CodeRow row) {
		return new PendingCode(row.email(), row.sentAt(), row.expiresAt(), MAX_ATTEMPTS - row.attempts(),
				!this.clock.instant().isBefore(row.expiresAt()));
	}

	private void lockAccount(UUID accountId) {
		this.jdbc.sql("SELECT id FROM identity.account WHERE id = :id FOR UPDATE")
			.param("id", accountId)
			.query(UUID.class)
			.optional()
			.orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
	}

	/** Whether {@code email} verifies another account; holds the address's lock until commit. */
	private boolean usedByAnotherAccount(String email, UUID accountId) {
		this.jdbc.sql("SELECT pg_advisory_xact_lock(hashtextextended(:key, 0))")
			.param("key", "identity.school_email:" + email)
			.query((rs, n) -> 1)
			.single();
		return this.jdbc.sql("""
				SELECT count(*) FROM identity.student_verification WHERE school_email = :email AND account_id <> :account
				""").param("email", email).param("account", accountId).query(Long.class).single() > 0;
	}

	/** At most 3 sends in the last hour; Retry-After is when the oldest of them leaves the hour. */
	private void requireAccountLimit(UUID accountId, Instant now) {
		List<Instant> recent = this.jdbc.sql("""
				SELECT sent_at FROM identity.school_email_code
				WHERE account_id = :account AND sent_at > :since ORDER BY sent_at DESC
				""")
			.param("account", accountId)
			.param("since", Timestamp.from(now.minus(Duration.ofHours(1))))
			.query((rs, n) -> rs.getTimestamp("sent_at").toInstant())
			.list();
		if (recent.size() >= SENDS_PER_ACCOUNT_AND_HOUR) {
			Instant oldestCounted = recent.get(SENDS_PER_ACCOUNT_AND_HOUR - 1);
			throw new RateLimitedException(Duration.between(now, oldestCounted.plus(Duration.ofHours(1))));
		}
	}

	/**
	 * At most 10 sends to one address per UTC day. The counter is raised first; a refusal rolls the
	 * transaction back, so a refused send is not counted.
	 */
	private void requireAddressLimit(String email, Instant now) {
		LocalDate day = LocalDate.ofInstant(now, ZoneOffset.UTC);
		int sends = this.jdbc.sql("""
				INSERT INTO identity.school_email_send_day (address_hash, day, sends) VALUES (:hash, :day, 1)
				ON CONFLICT (address_hash, day) DO UPDATE SET sends = identity.school_email_send_day.sends + 1
				RETURNING sends
				""").param("hash", sha256(email)).param("day", day).query(Integer.class).single();
		if (sends > SENDS_PER_ADDRESS_AND_DAY) {
			Instant nextDay = day.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant();
			throw new RateLimitedException(Duration.between(now, nextDay));
		}
	}

	private static Confirmation refused(ApiException refusal) {
		return new Confirmation.Refused(refusal);
	}

	static String hash(UUID codeId, String code) {
		return sha256(codeId + ":" + code);
	}

	static String sha256(String text) {
		try {
			return HexFormat.of()
				.formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
		}
		catch (NoSuchAlgorithmException ex) {
			throw new IllegalStateException("Every Java platform has SHA-256", ex);
		}
	}

}
