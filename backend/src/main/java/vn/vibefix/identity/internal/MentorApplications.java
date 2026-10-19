package vn.vibefix.identity.internal;

import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.jspecify.annotations.Nullable;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import vn.vibefix.audit.AuditActor;
import vn.vibefix.audit.AuditEntry;
import vn.vibefix.audit.AuditLog;
import vn.vibefix.audit.AuditObject;
import vn.vibefix.catalog.Catalog;
import vn.vibefix.catalog.Skill;
import vn.vibefix.files.FileKind;
import vn.vibefix.files.FileUpload;
import vn.vibefix.files.PreparedFile;
import vn.vibefix.files.StoredFile;
import vn.vibefix.files.StoredFiles;
import vn.vibefix.identity.MentorApplicationQueue;
import vn.vibefix.notification.Audience;
import vn.vibefix.notification.NotificationRequest;
import vn.vibefix.notification.Notifications;
import vn.vibefix.notification.Recipient;
import vn.vibefix.notification.Todos;
import vn.vibefix.shared.ApiException;
import vn.vibefix.shared.ErrorCode;
import vn.vibefix.shared.IdGenerator;

/**
 * The Mentor application, which stays the approved Mentor's profile (FR-74, AD-19). The first save
 * is the submission: it needs the current personal-data notice accepted, records that, reserves the
 * LinkedIn profile among live applications, closes the {@code identity.complete_mentor_application}
 * to-do, and tells every Administrator. Later saves edit the fields; the application stays as it
 * was decided or waiting. An optional document (an image or a PDF, AD-21) is read by Administrators
 * only; a draft row holds it until the first submission.
 * <p>
 * Every command locks the account row first, so one Mentor's saves and uploads run one at a time.
 */
@Component
class MentorApplications implements MentorApplicationQueue {

	static final String DOCUMENT_PURPOSE = "identity.mentor_document";

	static final String RECEIVED_NOTIFICATION = "identity.mentor_application_received";

	/** N-22's version, recorded with the submission like the personal-data notice's. */
	static final String N22_VERSION = "1.0";

	/** A Mentor profile keeps at most 20 Skills (FR-74). */
	static final int MAX_SKILLS = 20;

	/** What the Mentor fills in on MEN-5. */
	record Fields(String displayName, String experience, int yearsOfExperience, List<UUID> skillIds,
			String linkedinUrl, @Nullable String otherLink) {
	}

	record Document(UUID fileId, String name, String mediaType, long size) {
	}

	/** The application as MEN-5 shows it; a draft has only its document. */
	record Application(UUID accountId, @Nullable String displayName, @Nullable String experience,
			@Nullable Integer yearsOfExperience, List<UUID> skillIds, @Nullable String linkedinUrl,
			@Nullable String otherLink, @Nullable Document document, @Nullable Instant submittedAt) {
	}

	private final JdbcClient jdbc;

	private final Catalog catalog;

	private final StoredFiles files;

	private final JdbcPersonSignals signals;

	private final Accounts accounts;

	private final Notifications notifications;

	private final Todos todos;

	private final AuditLog auditLog;

	private final IdGenerator ids;

	private final Clock clock;

	private final IdentityProperties properties;

	MentorApplications(JdbcClient jdbc, Catalog catalog, StoredFiles files, JdbcPersonSignals signals, Accounts accounts,
			Notifications notifications, Todos todos, AuditLog auditLog, IdGenerator ids, Clock clock,
			IdentityProperties properties) {
		this.jdbc = jdbc;
		this.catalog = catalog;
		this.files = files;
		this.signals = signals;
		this.accounts = accounts;
		this.notifications = notifications;
		this.todos = todos;
		this.auditLog = auditLog;
		this.ids = ids;
		this.clock = clock;
		this.properties = properties;
	}

	@Override
	public Pending pending() {
		return this.jdbc.sql("""
				SELECT count(*) AS waiting, min(m.submitted_at) AS oldest
				FROM identity.mentor_application m JOIN identity.account a ON a.id = m.account_id
				WHERE m.submitted_at IS NOT NULL AND a.mentor_approval_status = 'PENDING'
				  AND a.locked_at IS NULL AND a.erased_at IS NULL
				""").query((rs, n) -> {
			Timestamp oldest = rs.getTimestamp("oldest");
			return new Pending(rs.getLong("waiting"), oldest == null ? null : oldest.toInstant());
		}).single();
	}

	Optional<Application> find(UUID accountId) {
		return this.jdbc.sql("SELECT * FROM identity.mentor_application WHERE account_id = :id")
			.param("id", accountId)
			.query((rs, n) -> application(rs))
			.optional();
	}

	/** The Skills with their current names, retired ones included, in the Mentor's order. */
	List<Skill> skills(List<UUID> skillIds) {
		return this.catalog.byIds(skillIds);
	}

	/**
	 * Saves the application; the first save submits it.
	 * @param acceptedNoticeVersion the personal-data notice version the Mentor accepted on MEN-5
	 * @return whether this save was the submission, or a rejected Mentor's resubmission
	 * @throws ApiException {@code VALIDATION} with {@code PRIVACY_NOTICE_REQUIRED}, {@code SKILL_INACTIVE}, or
	 * {@code SKILL_LIMIT}; {@code GUARD_FAILED} with {@code MENTOR_LINKEDIN_IN_USE}, or with
	 * {@code MENTOR_REAPPLY_TOO_SOON} and the detail {@code date} before a rejection's 30-day wait ends
	 */
	@Transactional
	boolean save(UUID accountId, Fields fields, @Nullable String acceptedNoticeVersion) {
		lockAccount(accountId);
		Optional<Application> existing = find(accountId);
		boolean resubmitting = requireReapplyOpen(accountId);
		boolean submitting = resubmitting || existing.map(Application::submittedAt).isEmpty();
		boolean needsNotice = existing.map(Application::submittedAt).isEmpty();
		if (needsNotice && !this.properties.privacyNoticeVersion().equals(acceptedNoticeVersion)) {
			throw new ApiException(ErrorCode.VALIDATION, IdentityReason.PRIVACY_NOTICE_REQUIRED);
		}
		List<UUID> skillIds = List.copyOf(new LinkedHashSet<>(fields.skillIds()));
		this.catalog.requireAtMost(skillIds.size(), MAX_SKILLS);
		Set<UUID> held = Set.copyOf(existing.map(Application::skillIds).orElse(List.of()));
		this.catalog.requireActive(skillIds.stream().filter(id -> !held.contains(id)).toList());
		Map.Entry<Integer, String> linkedin = this.signals.currentLinkedIn(fields.linkedinUrl())
			.orElseThrow(() -> new ApiException(ErrorCode.VALIDATION));
		requireLinkedInFree(accountId, fields.linkedinUrl());
		Instant now = this.clock.instant();
		try {
			this.jdbc.sql("""
					INSERT INTO identity.mentor_application
					    (account_id, display_name, experience, years_of_experience, skill_ids, skill_names, linkedin_url,
					     linkedin_hmac, linkedin_key_version, linkedin_reserved, other_link, privacy_notice_version,
					     n22_version, submitted_at, updated_at)
					VALUES (:account, :name, :experience, :years, CAST(:skills AS UUID[]), :skillNames, :linkedin,
					        :hmac, :version, TRUE, :other, :notice, :n22, :now, :now)
					ON CONFLICT (account_id) DO UPDATE SET
					    display_name = EXCLUDED.display_name, experience = EXCLUDED.experience,
					    years_of_experience = EXCLUDED.years_of_experience, skill_ids = EXCLUDED.skill_ids,
					    skill_names = EXCLUDED.skill_names, linkedin_url = EXCLUDED.linkedin_url,
					    linkedin_hmac = EXCLUDED.linkedin_hmac, linkedin_key_version = EXCLUDED.linkedin_key_version,
					    linkedin_reserved = TRUE, other_link = EXCLUDED.other_link,
					    privacy_notice_version = COALESCE(identity.mentor_application.privacy_notice_version,
					                                      EXCLUDED.privacy_notice_version),
					    n22_version = COALESCE(identity.mentor_application.n22_version, EXCLUDED.n22_version),
					    submitted_at = CASE WHEN :resubmit THEN EXCLUDED.submitted_at
					                        ELSE COALESCE(identity.mentor_application.submitted_at, EXCLUDED.submitted_at) END,
					    decision_id = CASE WHEN :resubmit THEN NULL ELSE identity.mentor_application.decision_id END,
					    updated_at = EXCLUDED.updated_at
					""")
				.param("account", accountId)
				.param("name", fields.displayName().strip())
				.param("experience", fields.experience().strip())
				.param("years", fields.yearsOfExperience())
				.param("skills", skillIds.stream().map(UUID::toString).collect(Collectors.joining(",", "{", "}")))
				.param("skillNames", this.catalog.byIds(skillIds).stream().map(Skill::name).collect(Collectors.joining(", ")))
				.param("linkedin", fields.linkedinUrl().strip())
				.param("hmac", linkedin.getValue())
				.param("version", linkedin.getKey())
				.param("other", fields.otherLink())
				.param("notice", this.properties.privacyNoticeVersion())
				.param("n22", N22_VERSION)
				.param("now", Timestamp.from(now))
				.param("resubmit", resubmitting)
				.update();
		}
		catch (DuplicateKeyException ex) {
			throw new ApiException(ErrorCode.GUARD_FAILED, IdentityReason.MENTOR_LINKEDIN_IN_USE);
		}
		this.signals.record(accountId, JdbcPersonSignals.Source.LINKEDIN, fields.linkedinUrl());
		if (resubmitting) {
			this.jdbc.sql("UPDATE identity.account SET mentor_approval_status = 'PENDING' WHERE id = :id")
				.param("id", accountId)
				.update();
		}
		if (submitting) {
			submitted(accountId, now, needsNotice);
		}
		else {
			this.auditLog.record(AuditEntry.of(AuditActor.user(accountId), "identity.mentor_application_updated",
					AuditObject.of("identity.account", accountId)));
		}
		return submitting;
	}

	/** Checks and, for an image, re-encodes a document before its transaction (AD-21). */
	PreparedFile prepareDocument(UUID accountId, String fileName, byte[] content) {
		return this.files.prepare(new FileUpload(DOCUMENT_PURPOSE, accountId, fileName, content,
				Set.of(FileKind.IMAGE, FileKind.PDF)));
	}

	/**
	 * Stores the document in place of the earlier one, which is deleted.
	 * @throws ApiException {@code GUARD_FAILED} with {@code MENTOR_REAPPLY_TOO_SOON} during a rejection's wait
	 */
	@Transactional
	Document storeDocument(UUID accountId, PreparedFile file) {
		lockAccount(accountId);
		requireReapplyOpen(accountId);
		Optional<Document> earlier = find(accountId).map(Application::document);
		StoredFile stored = this.files.store(file);
		Instant now = this.clock.instant();
		this.jdbc.sql("""
				INSERT INTO identity.mentor_application
				    (account_id, document_file_id, document_name, document_media_type, document_size, updated_at)
				VALUES (:account, :file, :name, :type, :size, :now)
				ON CONFLICT (account_id) DO UPDATE SET document_file_id = EXCLUDED.document_file_id,
				    document_name = EXCLUDED.document_name, document_media_type = EXCLUDED.document_media_type,
				    document_size = EXCLUDED.document_size, updated_at = EXCLUDED.updated_at
				""")
			.param("account", accountId)
			.param("file", stored.id())
			.param("name", stored.fileName())
			.param("type", stored.mediaType())
			.param("size", stored.size())
			.param("now", Timestamp.from(now))
			.update();
		earlier.ifPresent(document -> this.files.delete(document.fileId()));
		this.auditLog.record(AuditEntry.of(AuditActor.user(accountId), "identity.mentor_document_uploaded",
				AuditObject.of("identity.account", accountId)));
		return new Document(stored.id(), stored.fileName(), stored.mediaType(), stored.size());
	}

	/**
	 * Removes the document; removing none changes nothing.
	 * @throws ApiException {@code GUARD_FAILED} with {@code MENTOR_REAPPLY_TOO_SOON} during a rejection's wait
	 */
	@Transactional
	void deleteDocument(UUID accountId) {
		lockAccount(accountId);
		requireReapplyOpen(accountId);
		Optional<Document> document = find(accountId).map(Application::document);
		if (document.isEmpty()) {
			return;
		}
		this.jdbc.sql("""
				UPDATE identity.mentor_application SET document_file_id = NULL, document_name = NULL,
				    document_media_type = NULL, document_size = NULL, updated_at = :now
				WHERE account_id = :account
				""").param("account", accountId).param("now", Timestamp.from(this.clock.instant())).update();
		this.files.delete(document.get().fileId());
		this.auditLog.record(AuditEntry.of(AuditActor.user(accountId), "identity.mentor_document_removed",
				AuditObject.of("identity.account", accountId)));
	}

	private void submitted(UUID accountId, Instant now, boolean noticeAccepted) {
		if (noticeAccepted) {
			this.accounts.insertAcknowledgment(this.ids.newId(), accountId, SignIns.PRIVACY_NOTICE,
					this.properties.privacyNoticeVersion(), now);
		}
		this.todos.close(SignIns.COMPLETE_MENTOR_APPLICATION, accountId, accountId);
		List<Recipient> administrators = this.jdbc.sql("""
				SELECT a.id FROM identity.account a JOIN identity.administrator_credential c ON c.account_id = a.id
				WHERE a.role = 'ADMINISTRATOR' AND a.locked_at IS NULL AND a.erased_at IS NULL ORDER BY a.id
				""").query(UUID.class).list().stream().map(id -> new Recipient(id, Audience.ADMIN)).toList();
		if (!administrators.isEmpty()) {
			this.notifications.send(new NotificationRequest(RECEIVED_NOTIFICATION, this.ids.newId(), administrators,
					Map.of(), "/quan-tri/nguoi-huong-dan/" + accountId, null, null));
		}
		this.auditLog.record(AuditEntry.of(AuditActor.user(accountId), "identity.mentor_application_submitted",
				AuditObject.of("identity.account", accountId)));
	}

	/**
	 * Whether this save resubmits a rejected application: a rejected Mentor may save only once the
	 * 30-day wait of the latest rejection is over (FR-74).
	 */
	private boolean requireReapplyOpen(UUID accountId) {
		String status = this.jdbc.sql("SELECT mentor_approval_status FROM identity.account WHERE id = :id")
			.param("id", accountId)
			.query(String.class)
			.optional()
			.orElse(null);
		if (!"REJECTED".equals(status)) {
			return false;
		}
		Optional<Instant> reapplyAfter = this.jdbc.sql("""
				SELECT reapply_after FROM identity.mentor_decision WHERE account_id = :id
				ORDER BY decided_at DESC LIMIT 1
				""").param("id", accountId).query(Timestamp.class).optional().map(Timestamp::toInstant);
		if (reapplyAfter.isPresent() && this.clock.instant().isBefore(reapplyAfter.get())) {
			throw new ApiException(ErrorCode.GUARD_FAILED, IdentityReason.MENTOR_REAPPLY_TOO_SOON,
					Map.of("date", LocalDate.ofInstant(reapplyAfter.get(), StudentVerifications.VIETNAM).toString()));
		}
		return true;
	}

	/** The LinkedIn profile is free unless another live application reserves it, under any live key. */
	private void requireLinkedInFree(UUID accountId, String linkedinUrl) {
		List<String> hmacs = this.signals.allLinkedIn(linkedinUrl);
		if (hmacs.isEmpty()) {
			return;
		}
		boolean taken = this.jdbc.sql("""
				SELECT count(*) FROM identity.mentor_application
				WHERE linkedin_reserved AND linkedin_hmac IN (:hmacs) AND account_id <> :account
				""").param("hmacs", hmacs).param("account", accountId).query(Long.class).single() > 0;
		if (taken) {
			throw new ApiException(ErrorCode.GUARD_FAILED, IdentityReason.MENTOR_LINKEDIN_IN_USE);
		}
	}

	private void lockAccount(UUID accountId) {
		this.jdbc.sql("SELECT id FROM identity.account WHERE id = :id FOR UPDATE")
			.param("id", accountId)
			.query(UUID.class)
			.optional()
			.orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
	}

	private static Application application(ResultSet rs) throws SQLException {
		UUID file = rs.getObject("document_file_id", UUID.class);
		Timestamp submitted = rs.getTimestamp("submitted_at");
		return new Application(rs.getObject("account_id", UUID.class), rs.getString("display_name"),
				rs.getString("experience"), (Integer) rs.getObject("years_of_experience", Integer.class),
				uuids(rs.getArray("skill_ids")), rs.getString("linkedin_url"), rs.getString("other_link"),
				file == null ? null
						: new Document(file, rs.getString("document_name"), rs.getString("document_media_type"),
								rs.getLong("document_size")),
				submitted == null ? null : submitted.toInstant());
	}

	private static List<UUID> uuids(@Nullable Array array) throws SQLException {
		return array == null ? List.of() : new ArrayList<>(Arrays.asList((UUID[]) array.getArray()));
	}

}
