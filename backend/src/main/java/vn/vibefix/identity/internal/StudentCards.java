package vn.vibefix.identity.internal;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.text.Normalizer;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.jspecify.annotations.Nullable;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import vn.vibefix.audit.AuditActor;
import vn.vibefix.audit.AuditEntry;
import vn.vibefix.audit.AuditLog;
import vn.vibefix.audit.AuditObject;
import vn.vibefix.files.FileKind;
import vn.vibefix.files.FileUpload;
import vn.vibefix.files.PreparedFile;
import vn.vibefix.files.StoredFile;
import vn.vibefix.files.StoredFiles;
import vn.vibefix.identity.StudentCardQueue;
import vn.vibefix.notification.Audience;
import vn.vibefix.notification.NotificationRequest;
import vn.vibefix.notification.Notifications;
import vn.vibefix.notification.Recipient;
import vn.vibefix.shared.ApiException;
import vn.vibefix.shared.ErrorCode;
import vn.vibefix.shared.IdGenerator;
import vn.vibefix.timers.DeadlineRequest;
import vn.vibefix.timers.Deadlines;

/**
 * Student verification by a student card (FR-5, UX-DR27): the Student uploads an image or a PDF of
 * the card, kept by {@code files} under {@value #PURPOSE}; an Administrator approves it, recording
 * the card's student number and school, or rejects it with a reason. An approval verifies the account
 * as a school email code does ({@link StudentVerifications#verifyByCard}), and the card's school and
 * number verify at most one account. Every decision is audited and notified, and the file is deleted
 * 30 days later ({@link StudentCardDeletion}).
 * <p>
 * Commands lock the Student's account row first, as {@link StudentVerifications} does, so an upload,
 * a decision, a code entry, and the expiry of one account run one at a time.
 */
@Component
class StudentCards implements StudentCardQueue {

	/** The {@code files} purpose of a card, which {@link StudentCardFiles} answers for. */
	static final String PURPOSE = "identity.student_card";

	static final String APPROVED_NOTIFICATION = "identity.student_card_approved";

	static final String REJECTED_NOTIFICATION = "identity.student_card_rejected";

	/** How long a decided card's file is kept (FR-5). */
	static final Duration KEPT_AFTER_DECISION = Duration.ofDays(30);

	/** ADM-3's sorts and their columns. */
	private static final Map<String, String> SORTS = Map.of("uploadedAt", "c.uploaded_at", "decidedAt", "c.decided_at");

	enum Status {

		PENDING, APPROVED, REJECTED

	}

	/** The Student's latest card while it matters on STU-5: pending, or rejected since their last verification. */
	record CardView(UUID id, Status status, Instant uploadedAt, String fileName, @Nullable Instant decidedAt,
			@Nullable String rejectionReason) {
	}

	/** One row of ADM-3. */
	record QueueRow(UUID id, UUID accountId, @Nullable String githubLogin, @Nullable String realName,
			@Nullable String profileSchool, Status status, Instant uploadedAt, @Nullable Instant decidedAt,
			UUID fileId, String fileName, String mediaType, boolean fileDeleted, @Nullable String studentNumber,
			@Nullable String school, @Nullable String rejectionReason) {
	}

	/** A card's state, read under the account's lock. */
	private record Card(UUID id, UUID accountId, Status status, UUID fileId, @Nullable Instant fileDeletedAt) {
	}

	private final JdbcClient jdbc;

	private final StoredFiles files;

	private final StudentVerifications verifications;

	private final Deadlines deadlines;

	private final Notifications notifications;

	private final AuditLog auditLog;

	private final IdGenerator ids;

	private final Clock clock;

	StudentCards(JdbcClient jdbc, StoredFiles files, StudentVerifications verifications, Deadlines deadlines,
			Notifications notifications, AuditLog auditLog, IdGenerator ids, Clock clock) {
		this.jdbc = jdbc;
		this.files = files;
		this.verifications = verifications;
		this.deadlines = deadlines;
		this.notifications = notifications;
		this.auditLog = auditLog;
		this.ids = ids;
		this.clock = clock;
	}

	@Override
	public Pending pending() {
		return this.jdbc.sql("""
				SELECT count(*) AS waiting, min(uploaded_at) AS oldest FROM identity.student_card WHERE status = 'PENDING'
				""").query((rs, n) -> {
			Timestamp oldest = rs.getTimestamp("oldest");
			return new Pending(rs.getLong("waiting"), oldest == null ? null : oldest.toInstant());
		}).single();
	}

	/**
	 * Checks and, for an image, re-encodes the card before the upload's transaction (AD-21): only an
	 * image or a PDF. A Student who may not upload now is refused first, before any work.
	 * @throws ApiException {@code CONFLICT} while verified with renewal not open, or while a card is pending
	 */
	PreparedFile prepare(UUID accountId, String fileName, byte[] content) {
		requireMayUpload(accountId);
		return this.files.prepare(new FileUpload(PURPOSE, accountId, fileName, content, Set.of(FileKind.IMAGE, FileKind.PDF)));
	}

	/** Stores the prepared card and puts it in ADM-3's queue. */
	@Transactional
	void upload(UUID accountId, PreparedFile file) {
		lockAccount(accountId);
		requireMayUpload(accountId);
		StoredFile stored = this.files.store(file);
		UUID id = this.ids.newId();
		this.jdbc.sql("""
				INSERT INTO identity.student_card (id, account_id, file_id, file_name, media_type, status, uploaded_at)
				VALUES (:id, :account, :file, :name, :type, 'PENDING', :now)
				""")
			.param("id", id)
			.param("account", accountId)
			.param("file", stored.id())
			.param("name", stored.fileName())
			.param("type", stored.mediaType())
			.param("now", Timestamp.from(stored.storedAt()))
			.update();
		this.auditLog.record(AuditEntry.of(AuditActor.user(accountId), "identity.student_card_uploaded",
				AuditObject.of("identity.account", accountId), AuditObject.of("identity.student_card", id)));
	}

	/** The card STU-5 shows: the latest one while it is pending, or rejected since the last verification. */
	@Transactional(readOnly = true)
	Optional<CardView> current(UUID accountId) {
		Optional<Instant> verifiedAt = this.verifications.verifiedAt(accountId);
		return this.jdbc.sql("""
				SELECT id, status, uploaded_at, file_name, decided_at, rejection_reason FROM identity.student_card
				WHERE account_id = :account ORDER BY uploaded_at DESC LIMIT 1
				""")
			.param("account", accountId)
			.query((rs, n) -> new CardView(rs.getObject("id", UUID.class), Status.valueOf(rs.getString("status")),
					rs.getTimestamp("uploaded_at").toInstant(), rs.getString("file_name"), instant(rs, "decided_at"),
					rs.getString("rejection_reason")))
			.optional()
			.filter(card -> card.status() == Status.PENDING || (card.status() == Status.REJECTED
					&& (verifiedAt.isEmpty() || verifiedAt.get().isBefore(card.decidedAt()))));
	}

	/**
	 * ADM-3's page of cards in {@code status}: by default the pending ones oldest first, and decided
	 * ones newest decision first.
	 * @throws ApiException {@code VALIDATION} for a sort other than {@code uploadedAt} or {@code decidedAt}
	 */
	@Transactional(readOnly = true)
	Page<QueueRow> queue(Status status, Pageable pageable) {
		List<String> order = new ArrayList<>();
		Sort sort = pageable.getSort();
		if (sort.isUnsorted()) {
			order.add(status == Status.PENDING ? "c.uploaded_at ASC" : "c.decided_at DESC");
		}
		for (Sort.Order part : sort) {
			String column = SORTS.get(part.getProperty());
			if (column == null) {
				throw new ApiException(ErrorCode.VALIDATION);
			}
			order.add(column + (part.isAscending() ? " ASC" : " DESC"));
		}
		order.add("c.id ASC");
		List<QueueRow> rows = this.jdbc.sql("""
				SELECT c.id, c.account_id, a.github_login, p.student_name, p.student_school AS profile_school, c.status,
				       c.uploaded_at, c.decided_at, c.file_id, c.file_name, c.media_type, c.file_deleted_at,
				       c.student_number, c.school, c.rejection_reason
				FROM identity.student_card c
				JOIN identity.account a ON a.id = c.account_id
				LEFT JOIN identity.profile p ON p.account_id = c.account_id
				WHERE c.status = :status
				ORDER BY %s
				LIMIT :size OFFSET :offset
				""".formatted(String.join(", ", order)))
			.param("status", status.name())
			.param("size", pageable.getPageSize())
			.param("offset", pageable.getOffset())
			.query((rs, n) -> queueRow(rs))
			.list();
		long total = this.jdbc.sql("SELECT count(*) FROM identity.student_card WHERE status = :status")
			.param("status", status.name())
			.query(Long.class)
			.single();
		return new PageImpl<>(rows, pageable, total);
	}

	/**
	 * Approves a pending card, recording what it says, and verifies its Student.
	 * @throws ApiException {@code NOT_FOUND} for no such card; {@code CONFLICT} for a decided one, or with
	 * {@code ALREADY_USED_FOR_VERIFICATION} when that school and number verify another account;
	 * {@code VALIDATION} when either is empty once normalized
	 */
	@Transactional
	QueueRow approve(UUID administrator, UUID cardId, String studentNumber, String school) {
		Card card = lockPendingCard(cardId);
		String number = studentNumber.strip();
		String schoolName = school.strip();
		String key = cardKey(schoolName, number);
		if (this.verifications.cardKeyUsedByAnotherAccount(key, card.accountId())) {
			throw new ApiException(ErrorCode.CONFLICT, IdentityReason.ALREADY_USED_FOR_VERIFICATION);
		}
		Instant now = this.clock.instant().truncatedTo(ChronoUnit.MICROS);
		this.jdbc.sql("""
				UPDATE identity.student_card
				SET status = 'APPROVED', decided_at = :now, decided_by = :admin, student_number = :number, school = :school
				WHERE id = :id
				""")
			.param("id", cardId)
			.param("now", Timestamp.from(now))
			.param("admin", administrator)
			.param("number", number)
			.param("school", schoolName)
			.update();
		this.verifications.verifyByCard(card.accountId(), key, cardId, now);
		decided(administrator, card, now, "identity.student_card_approved", APPROVED_NOTIFICATION, Map.of());
		return row(cardId);
	}

	/**
	 * Rejects a pending card with a reason the Student reads.
	 * @throws ApiException {@code NOT_FOUND} for no such card; {@code CONFLICT} for a decided one
	 */
	@Transactional
	QueueRow reject(UUID administrator, UUID cardId, String reason) {
		Card card = lockPendingCard(cardId);
		String text = reason.strip();
		Instant now = this.clock.instant().truncatedTo(ChronoUnit.MICROS);
		this.jdbc.sql("""
				UPDATE identity.student_card
				SET status = 'REJECTED', decided_at = :now, decided_by = :admin, rejection_reason = :reason
				WHERE id = :id
				""")
			.param("id", cardId)
			.param("now", Timestamp.from(now))
			.param("admin", administrator)
			.param("reason", text)
			.update();
		decided(administrator, card, now, "identity.student_card_rejected", REJECTED_NOTIFICATION, Map.of("reason", text));
		return row(cardId);
	}

	/**
	 * The school and the student number as one comparable key: the school without accents, in lower
	 * case, with each run of other characters as one space; the number with letters and digits only,
	 * in upper case.
	 * @throws ApiException {@code VALIDATION} when either part is empty
	 */
	static String cardKey(String school, String studentNumber) {
		String schoolKey = Normalizer.normalize(school.replace('đ', 'd').replace('Đ', 'D'), Normalizer.Form.NFD)
			.replaceAll("\\p{M}", "")
			.toLowerCase(Locale.ROOT)
			.replaceAll("[^\\p{L}\\p{N}]+", " ")
			.strip();
		String numberKey = Normalizer.normalize(studentNumber, Normalizer.Form.NFKC)
			.replaceAll("[^\\p{L}\\p{N}]", "")
			.toUpperCase(Locale.ROOT);
		if (schoolKey.isEmpty() || numberKey.isEmpty()) {
			throw new ApiException(ErrorCode.VALIDATION);
		}
		return schoolKey + "|" + numberKey;
	}

	private void decided(UUID administrator, Card card, Instant now, String action, String notification,
			Map<String, String> params) {
		this.deadlines.schedule(new DeadlineRequest(StudentCardDeletion.KIND.key(), card.id(), now.toString(), null,
				now.plus(KEPT_AFTER_DECISION)));
		this.notifications.send(new NotificationRequest(notification, card.id(),
				List.of(new Recipient(card.accountId(), Audience.SUBJECT)), params, SignIns.VERIFICATION_PATH, null,
				card.accountId()));
		this.auditLog.record(AuditEntry.of(AuditActor.user(administrator), action,
				AuditObject.of("identity.student_card", card.id()), AuditObject.of("identity.account", card.accountId())));
	}

	private void requireMayUpload(UUID accountId) {
		if (!this.verifications.mayVerify(accountId)) {
			throw new ApiException(ErrorCode.CONFLICT);
		}
		boolean pending = this.jdbc.sql("""
				SELECT count(*) FROM identity.student_card WHERE account_id = :account AND status = 'PENDING'
				""").param("account", accountId).query(Long.class).single() > 0;
		if (pending) {
			throw new ApiException(ErrorCode.CONFLICT);
		}
	}

	/** The card, pending, under its Student's account lock. */
	private Card lockPendingCard(UUID cardId) {
		UUID accountId = this.jdbc.sql("SELECT account_id FROM identity.student_card WHERE id = :id")
			.param("id", cardId)
			.query(UUID.class)
			.optional()
			.orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
		lockAccount(accountId);
		Card card = this.jdbc.sql("""
				SELECT id, account_id, status, file_id, file_deleted_at FROM identity.student_card WHERE id = :id FOR UPDATE
				""").param("id", cardId).query((rs, n) -> card(rs)).single();
		if (card.status() != Status.PENDING) {
			throw new ApiException(ErrorCode.CONFLICT);
		}
		return card;
	}

	private void lockAccount(UUID accountId) {
		this.jdbc.sql("SELECT id FROM identity.account WHERE id = :id FOR UPDATE")
			.param("id", accountId)
			.query(UUID.class)
			.optional()
			.orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
	}

	private QueueRow row(UUID cardId) {
		return this.jdbc.sql("""
				SELECT c.id, c.account_id, a.github_login, p.student_name, p.student_school AS profile_school, c.status,
				       c.uploaded_at, c.decided_at, c.file_id, c.file_name, c.media_type, c.file_deleted_at,
				       c.student_number, c.school, c.rejection_reason
				FROM identity.student_card c
				JOIN identity.account a ON a.id = c.account_id
				LEFT JOIN identity.profile p ON p.account_id = c.account_id
				WHERE c.id = :id
				""").param("id", cardId).query((rs, n) -> queueRow(rs)).single();
	}

	private static QueueRow queueRow(ResultSet rs) throws SQLException {
		return new QueueRow(rs.getObject("id", UUID.class), rs.getObject("account_id", UUID.class),
				rs.getString("github_login"), rs.getString("student_name"), rs.getString("profile_school"),
				Status.valueOf(rs.getString("status")), rs.getTimestamp("uploaded_at").toInstant(),
				instant(rs, "decided_at"), rs.getObject("file_id", UUID.class), rs.getString("file_name"),
				rs.getString("media_type"), rs.getTimestamp("file_deleted_at") != null, rs.getString("student_number"),
				rs.getString("school"), rs.getString("rejection_reason"));
	}

	private static Card card(ResultSet rs) throws SQLException {
		return new Card(rs.getObject("id", UUID.class), rs.getObject("account_id", UUID.class),
				Status.valueOf(rs.getString("status")), rs.getObject("file_id", UUID.class), instant(rs, "file_deleted_at"));
	}

	private static @Nullable Instant instant(ResultSet rs, String column) throws SQLException {
		Timestamp value = rs.getTimestamp(column);
		return value == null ? null : value.toInstant();
	}

}
