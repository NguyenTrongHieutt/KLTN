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
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.LinkedHashMap;
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
import vn.vibefix.identity.Notices;
import vn.vibefix.identity.StudentVerification;
import vn.vibefix.identity.internal.VerificationRows.Ending;
import vn.vibefix.identity.internal.VerificationRows.Facts;
import vn.vibefix.identity.internal.VerificationRows.NewRow;
import vn.vibefix.identity.internal.VerificationRows.Provider;
import vn.vibefix.identity.internal.VerificationRows.Row;
import vn.vibefix.notification.EmailClass;
import vn.vibefix.notification.EmailRequest;
import vn.vibefix.notification.Emails;
import vn.vibefix.notification.Todos;
import vn.vibefix.settings.Settings;
import vn.vibefix.shared.ApiException;
import vn.vibefix.shared.ErrorCode;
import vn.vibefix.shared.IdGenerator;
import vn.vibefix.shared.RateLimitedException;

/**
 * Student verification by a school email code (FR-5, FR-80, AD-34, AD-42), for any person's account. A
 * code is 6 digits, valid for 10 minutes, with 5 attempts; it is kept only as a hash, keyed by the
 * address HMAC, and deleted when used or replaced. The address itself is never stored: it is in memory
 * and in the email External Operation's personal data, which is purged once the email completes.
 * Sends are limited to 3 per account per clock hour and 10 per address per UTC day, each counted in
 * its own rows. A send after N-26 records the {@code VERIFY_STUDENT} acknowledgment; a correct code
 * writes an {@code identity.verification} row with the school matched from the allowlist and the
 * declared name and major, locked while it is live, valid until the next 30 September. It projects
 * the facts into the profile, records the {@code SCHOOL_EMAIL} signal, schedules the reminder and the
 * expiry ({@link VerificationDeadlines}), audits, and closes the {@code identity.verify_student} to-do.
 * From 14 days before the expiry the account may renew, which asks for the address only.
 * <p>
 * Every command locks the account row first, so one account's sends and attempts run one at a time;
 * a transaction-scoped advisory lock on the subject HMAC makes "verifies at most one account" hold
 * across accounts, with the partial unique index as the backstop.
 */
@Component
class StudentVerifications implements StudentVerification {

	/** The email template and notification type of the code (EXPERIENCE.md Notification Inventory). */
	static final String SCHOOL_EMAIL_CODE = "identity.school_email_code";

	/** The person-link signal kind whose keyed HMAC is an email address's subject (AD-19). */
	static final String EMAIL_SUBJECT = "EMAIL";

	/** The kind whose keyed HMAC is a student card's subject: its school and number ({@link StudentCards#cardKey}). */
	static final String CARD_SUBJECT = "STUDENT_CARD";

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

	/**
	 * The code waiting for its entry; {@code expired} once its 10 minutes are over.
	 * @param school the allowlisted domain the address matched: the code step names it, never the address
	 */
	record PendingCode(String school, Instant sentAt, Instant expiresAt, int attemptsLeft, boolean expired) {
	}

	/**
	 * @param verification the live row while {@code VERIFIED}; the lapsed or expired row, without its facts,
	 * while {@code EXPIRED}; none while {@code UNVERIFIED}
	 * @param renewalOpen a current verification within 14 days of its expiry, which may be renewed
	 */
	record View(Status status, @Nullable Row verification, @Nullable PendingCode pendingCode, boolean renewalOpen) {
	}

	/** How a code entry ended. The attempt counts even when the entry is refused. */
	sealed interface Confirmation {

		record Verified(View view) implements Confirmation {
		}

		record Refused(ApiException refusal) implements Confirmation {
		}

	}

	private record CodeRow(UUID id, String addressHmac, int keyVersion, String codeHash, Instant sentAt,
			Instant expiresAt, int attempts, String school, String name, String major) {
	}

	private final JdbcClient jdbc;

	private final Settings settings;

	private final Emails emails;

	private final Todos todos;

	private final AuditLog auditLog;

	private final IdGenerator ids;

	private final Clock clock;

	private final VerificationDeadlines deadlines;

	private final JdbcPersonSignals signals;

	private final PersonSignalKeys keys;

	private final VerificationRows rows;

	private final Accounts accounts;

	private final SecureRandom random = new SecureRandom();

	StudentVerifications(JdbcClient jdbc, Settings settings, Emails emails, Todos todos, AuditLog auditLog,
			IdGenerator ids, Clock clock, VerificationDeadlines deadlines, JdbcPersonSignals signals, PersonSignalKeys keys,
			VerificationRows rows, Accounts accounts) {
		this.jdbc = jdbc;
		this.settings = settings;
		this.emails = emails;
		this.todos = todos;
		this.auditLog = auditLog;
		this.ids = ids;
		this.clock = clock;
		this.deadlines = deadlines;
		this.signals = signals;
		this.keys = keys;
		this.rows = rows;
		this.accounts = accounts;
	}

	@Override
	public boolean isVerified(UUID accountId) {
		return currentVerification(accountId).isPresent();
	}

	/** The account's verification status and the code waiting for its entry. No address is in it. */
	@Transactional(readOnly = true)
	View view(UUID accountId) {
		Optional<Row> latest = this.rows.latest(accountId);
		Status status = latest.map(this::status).orElse(Status.UNVERIFIED);
		Row verification = switch (status) {
			case VERIFIED -> latest.get();
			// "Chưa xác minh" at once: a lapsed row's facts are no longer shown, though the Deadline has yet to end it.
			case EXPIRED -> withoutFacts(latest.get());
			case UNVERIFIED -> null;
		};
		boolean renewalOpen = status == Status.VERIFIED && renewalOpen(latest.get());
		PendingCode pending = status == Status.VERIFIED && !renewalOpen ? null
				: pending(accountId).map(this::pendingView).orElse(null);
		return new View(status, verification, pending, renewalOpen);
	}

	/**
	 * Records N-26 and emails a new code to {@code address}, replacing the pending one. A first verification
	 * declares {@code name} and {@code major}; a renewal keeps the locked ones and ignores these.
	 * @throws ApiException {@code CONFLICT} when the account is verified and renewal is not open yet;
	 * {@code GUARD_FAILED} with {@code EMAIL_DOMAIN_NOT_ALLOWED} or {@code STUDENT_EMAIL_IN_USE};
	 * {@code VALIDATION} naming {@code name} or {@code major} when a first verification lacks them
	 * @throws RateLimitedException past either send limit
	 */
	@Transactional
	View send(UUID accountId, String address, @Nullable String name, @Nullable String major) {
		String email = normalize(address);
		lockAccount(accountId);
		Optional<Row> current = currentVerification(accountId);
		boolean renewal = current.isPresent();
		if (renewal && !renewalOpen(current.get())) {
			throw new ApiException(ErrorCode.CONFLICT);
		}
		String school = this.settings.schoolOf(email)
			.orElseThrow(() -> new ApiException(ErrorCode.GUARD_FAILED, IdentityReason.EMAIL_DOMAIN_NOT_ALLOWED));
		Facts locked = renewal ? current.get().facts() : null;
		String declaredName = locked != null && locked.name() != null ? locked.name() : blankToNull(name);
		String declaredMajor = locked != null && locked.major() != null ? locked.major() : blankToNull(major);
		Map<String, String> missing = new LinkedHashMap<>();
		if (declaredName == null) {
			missing.put("name", "NotBlank");
		}
		if (declaredMajor == null) {
			missing.put("major", "NotBlank");
		}
		if (!missing.isEmpty()) {
			throw Profiles.invalid(missing);
		}
		int version = this.keys.currentVersion();
		String hmac = this.keys.current(EMAIL_SUBJECT, email);
		this.rows.lockSubject(hmac);
		if (this.rows.liveElsewhere(this.keys.all(EMAIL_SUBJECT, email), accountId)) {
			throw new ApiException(ErrorCode.GUARD_FAILED, IdentityReason.STUDENT_EMAIL_IN_USE);
		}
		Instant now = this.clock.instant();
		requireAccountLimit(accountId, now);
		requireAddressLimit(hmac, now);
		this.jdbc.sql("DELETE FROM identity.school_email_code WHERE account_id = :account")
			.param("account", accountId)
			.update();
		UUID id = this.ids.newId();
		String code = "%06d".formatted(this.random.nextInt(1_000_000));
		this.jdbc.sql("""
				INSERT INTO identity.school_email_code (id, account_id, address_hmac, key_version, code_hash, sent_at,
				    expires_at, school, declared_name, declared_major, renewal)
				VALUES (:id, :account, :hmac, :version, :hash, :now, :expires, :school, :name, :major, :renewal)
				""")
			.param("id", id)
			.param("account", accountId)
			.param("hmac", hmac)
			.param("version", version)
			.param("hash", hash(id, code))
			.param("now", Timestamp.from(now))
			.param("expires", Timestamp.from(now.plus(CODE_LIFETIME)))
			.param("school", school)
			.param("name", declaredName)
			.param("major", declaredMajor)
			.param("renewal", renewal)
			.update();
		consent(accountId, now);
		// The address goes only to the email operation's personal data, purged once the email completes.
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
		Optional<Row> live = this.rows.live(accountId);
		Optional<Row> current = live.filter(this::current);
		if (current.isPresent() && !renewalOpen(current.get())) {
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
		// A used code goes, whatever follows: it cannot be entered twice.
		this.jdbc.sql("DELETE FROM identity.school_email_code WHERE id = :id").param("id", row.id()).update();
		this.rows.lockSubject(row.addressHmac());
		if (this.rows.liveElsewhere(Map.of(row.keyVersion(), row.addressHmac()), accountId)) {
			return refused(new ApiException(ErrorCode.GUARD_FAILED, IdentityReason.STUDENT_EMAIL_IN_USE));
		}
		// PostgreSQL keeps microseconds; the views and exports read back the same instant.
		Instant verifiedAt = now.truncatedTo(ChronoUnit.MICROS);
		LocalDate validUntil = validUntil(LocalDate.ofInstant(now, VIETNAM));
		live.ifPresent(found -> this.rows.end(found, current.isPresent() ? Ending.REPLACED : Ending.EXPIRED, verifiedAt));
		UUID verification = this.ids.newId();
		this.rows.insert(new NewRow(verification, accountId, Provider.EMAIL_CODE, row.addressHmac(), row.keyVersion(),
				new Facts(row.school(), row.name(), row.major(), VerificationRows.month(verifiedAt)),
				Notices.VERIFY_STUDENT_VERSION, verifiedAt, validUntil), verifiedAt);
		this.deadlines.schedule(accountId, verification, validUntil, now);
		this.auditLog.record(AuditEntry.of(AuditActor.user(accountId), "identity.student_verified",
				AuditObject.of("identity.account", accountId), AuditObject.of("identity.verification", verification)));
		this.todos.close(SignIns.VERIFY_STUDENT, accountId, accountId);
		// The same keyed HMAC as the subject's: the address is gone by now (AD-19).
		this.signals.recordHmacs(accountId, JdbcPersonSignals.Source.SCHOOL_EMAIL,
				Map.of(row.keyVersion(), row.addressHmac()));
		return new Confirmation.Verified(view(accountId));
	}

	/**
	 * "Bỏ xác minh" (FR-80): ends the current verification {@code UNLINKED}, deleting its public facts and
	 * the profile's projection of them in the same transaction. The subject HMAC stays on the ended row,
	 * as N-26 states (AD-24); its reminder and expiry find it ended and are obsolete.
	 * @throws ApiException {@code NOT_FOUND} without a current verification
	 */
	@Transactional
	void unlink(UUID accountId) {
		lockAccount(accountId);
		Row row = currentVerification(accountId).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
		this.rows.end(row, Ending.UNLINKED, this.clock.instant().truncatedTo(ChronoUnit.MICROS));
		this.auditLog.record(AuditEntry.of(AuditActor.user(accountId), "identity.student_verification_unlinked",
				AuditObject.of("identity.account", accountId), AuditObject.of("identity.verification", row.id())));
	}

	/**
	 * Verifies the account by an approved student card (FR-5, until Story 19.7), as a correct code does:
	 * until the next 30 September, with the reminder and the expiry, and the {@code identity.verify_student}
	 * to-do closed. The row's subject is the card key's HMAC, its school the card's, and a renewal keeps the
	 * locked name and major. The caller holds the account's lock and the subject's
	 * ({@link #cardKeyUsedByAnotherAccount}).
	 */
	void verifyByCard(UUID accountId, String cardKey, String school, Instant now) {
		Instant verifiedAt = now.truncatedTo(ChronoUnit.MICROS);
		LocalDate validUntil = validUntil(LocalDate.ofInstant(now, VIETNAM));
		Optional<Row> live = this.rows.live(accountId);
		Optional<Facts> locked = live.filter(this::current).map(Row::facts);
		live.ifPresent(found -> this.rows.end(found, current(found) ? Ending.REPLACED : Ending.EXPIRED, verifiedAt));
		UUID verification = this.ids.newId();
		this.rows.insert(new NewRow(verification, accountId, Provider.STUDENT_CARD,
				this.keys.current(CARD_SUBJECT, cardKey), this.keys.currentVersion(),
				new Facts(school, locked.map(Facts::name).orElse(null), locked.map(Facts::major).orElse(null),
						VerificationRows.month(verifiedAt)),
				null, verifiedAt, validUntil), verifiedAt);
		this.deadlines.schedule(accountId, verification, validUntil, now);
		this.todos.close(SignIns.VERIFY_STUDENT, accountId, accountId);
	}

	/** Whether {@code cardKey} verifies another account, under any key version; holds the subject's lock until commit. */
	boolean cardKeyUsedByAnotherAccount(String cardKey, UUID accountId) {
		this.rows.lockSubject(this.keys.current(CARD_SUBJECT, cardKey));
		return this.rows.liveElsewhere(this.keys.all(CARD_SUBJECT, cardKey), accountId);
	}

	/** Whether the account may verify now: it is not verified, or its renewal is open. */
	boolean mayVerify(UUID accountId) {
		return currentVerification(accountId).filter(found -> !renewalOpen(found)).isEmpty();
	}

	/**
	 * The live row while it is current: not ended, and today in Vietnam on or before its last valid day.
	 * A lapsed row is none, though its expiry Deadline has yet to end it.
	 */
	Optional<Row> currentVerification(UUID accountId) {
		return this.rows.live(accountId).filter(this::current);
	}

	/** When the account last verified, if it ever did. */
	Optional<Instant> verifiedAt(UUID accountId) {
		return this.rows.lastVerifiedAt(accountId);
	}

	/**
	 * A school year's last day (FR-5): this year's 30 September, or next year's from 17 September on,
	 * when renewal is open, so a verification in the last 14 days counts for the next school year.
	 */
	static LocalDate validUntil(LocalDate today) {
		LocalDate thisYear = RENEWAL.atYear(today.getYear());
		return today.isBefore(renewalOpens(thisYear)) ? thisYear : RENEWAL.atYear(today.getYear() + 1);
	}

	/** The first day a verification valid through {@code validUntil} may be renewed: 14 days before it expires. */
	static LocalDate renewalOpens(LocalDate validUntil) {
		return validUntil.plusDays(1).minusDays(VerificationDeadlines.LEAD.toDays());
	}

	/** An address as compared and hashed: without surrounding spaces, in lower case. */
	static String normalize(String address) {
		return address.strip().toLowerCase(Locale.ROOT);
	}

	/** Records N-26's acceptance, once per version, in the send's transaction: before the email is requested. */
	private void consent(UUID accountId, Instant now) {
		UUID acknowledgment = this.ids.newId();
		if (this.accounts.insertAcknowledgment(acknowledgment, accountId, Notices.VERIFY_STUDENT,
				Notices.VERIFY_STUDENT_VERSION, now)) {
			this.auditLog.record(AuditEntry.of(AuditActor.user(accountId), "identity.notice_acknowledged",
					AuditObject.of("identity.account", accountId), AuditObject.of("identity.acknowledgment", acknowledgment)));
		}
	}

	private Status status(Row row) {
		if (row.live()) {
			return current(row) ? Status.VERIFIED : Status.EXPIRED;
		}
		return row.endedReason() == Ending.EXPIRED ? Status.EXPIRED : Status.UNVERIFIED;
	}

	private static Row withoutFacts(Row row) {
		return new Row(row.id(), row.accountId(), row.provider(), null, row.consentVersion(), row.verifiedAt(),
				row.validUntil(), row.endedAt(), row.endedReason());
	}

	private LocalDate today() {
		return LocalDate.ofInstant(this.clock.instant(), VIETNAM);
	}

	private boolean current(Row row) {
		return row.live() && !today().isAfter(row.validUntil());
	}

	private boolean renewalOpen(Row row) {
		return current(row) && !today().isBefore(renewalOpens(row.validUntil()));
	}

	private Optional<CodeRow> pending(UUID accountId) {
		return this.jdbc.sql("""
				SELECT id, address_hmac, key_version, code_hash, sent_at, expires_at, attempts, school, declared_name,
				       declared_major
				FROM identity.school_email_code WHERE account_id = :account
				""")
			.param("account", accountId)
			.query((rs, n) -> new CodeRow(rs.getObject("id", UUID.class), rs.getString("address_hmac"),
					rs.getInt("key_version"), rs.getString("code_hash"), rs.getTimestamp("sent_at").toInstant(),
					rs.getTimestamp("expires_at").toInstant(), rs.getInt("attempts"), rs.getString("school"),
					rs.getString("declared_name"), rs.getString("declared_major")))
			.optional();
	}

	private PendingCode pendingView(CodeRow row) {
		return new PendingCode(row.school(), row.sentAt(), row.expiresAt(), MAX_ATTEMPTS - row.attempts(),
				!this.clock.instant().isBefore(row.expiresAt()));
	}

	private void lockAccount(UUID accountId) {
		this.jdbc.sql("SELECT id FROM identity.account WHERE id = :id FOR UPDATE")
			.param("id", accountId)
			.query(UUID.class)
			.optional()
			.orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
	}

	/**
	 * At most 3 sends per account in one clock hour. The counter is raised first; a refusal rolls the
	 * transaction back, so a refused send is not counted. Retry-After is when the next hour begins.
	 */
	private void requireAccountLimit(UUID accountId, Instant now) {
		Instant hour = now.truncatedTo(ChronoUnit.HOURS);
		int sends = this.jdbc.sql("""
				INSERT INTO identity.school_email_send_hour (account_id, hour, sends) VALUES (:account, :hour, 1)
				ON CONFLICT (account_id, hour) DO UPDATE SET sends = identity.school_email_send_hour.sends + 1
				RETURNING sends
				""").param("account", accountId).param("hour", Timestamp.from(hour)).query(Integer.class).single();
		if (sends > SENDS_PER_ACCOUNT_AND_HOUR) {
			throw new RateLimitedException(Duration.between(now, hour.plus(Duration.ofHours(1))));
		}
	}

	/**
	 * At most 10 sends to one address per UTC day, counted by its HMAC. The counter is raised first; a
	 * refusal rolls the transaction back, so a refused send is not counted.
	 */
	private void requireAddressLimit(String addressHmac, Instant now) {
		LocalDate day = LocalDate.ofInstant(now, ZoneOffset.UTC);
		int sends = this.jdbc.sql("""
				INSERT INTO identity.school_email_send_day (address_hmac, day, sends) VALUES (:hmac, :day, 1)
				ON CONFLICT (address_hmac, day) DO UPDATE SET sends = identity.school_email_send_day.sends + 1
				RETURNING sends
				""").param("hmac", addressHmac).param("day", day).query(Integer.class).single();
		if (sends > SENDS_PER_ADDRESS_AND_DAY) {
			Instant nextDay = day.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant();
			throw new RateLimitedException(Duration.between(now, nextDay));
		}
	}

	private static @Nullable String blankToNull(@Nullable String value) {
		return value == null || value.isBlank() ? null : value.strip();
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
