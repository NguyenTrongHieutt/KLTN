package vn.vibefix.identity.internal;

import java.sql.Timestamp;
import java.time.Clock;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import vn.vibefix.audit.AuditActor;
import vn.vibefix.audit.AuditEntry;
import vn.vibefix.audit.AuditLog;
import vn.vibefix.audit.AuditObject;
import vn.vibefix.files.StoredFiles;
import vn.vibefix.timers.DeadlineHandler;
import vn.vibefix.timers.DeadlineKind;
import vn.vibefix.timers.DeadlineOutcome;
import vn.vibefix.timers.DueDeadline;

/**
 * {@value #KEY} (FR-5, AD-7): 30 days after a card's decision its file is deleted, and the deletion
 * is checked before it is recorded. A file still there throws, so the poller tries again later; a
 * card already gone (erased with its account) or already deleted leaves the Deadline {@code OBSOLETE}.
 * The decision time is the generation, and a decision happens once, so nothing else replaces it.
 */
@Component
class StudentCardDeletion implements DeadlineHandler {

	static final String KEY = "identity.student_card_deletion";

	static final DeadlineKind KIND = new DeadlineKind(KEY, false, false);

	private final JdbcClient jdbc;

	private final StoredFiles files;

	private final AuditLog auditLog;

	private final Clock clock;

	StudentCardDeletion(JdbcClient jdbc, StoredFiles files, AuditLog auditLog, Clock clock) {
		this.jdbc = jdbc;
		this.files = files;
		this.auditLog = auditLog;
		this.clock = clock;
	}

	@Override
	public DeadlineKind kind() {
		return KIND;
	}

	@Override
	public DeadlineOutcome handle(DueDeadline deadline) {
		record Row(UUID fileId, UUID accountId) {
		}
		Optional<Row> card = this.jdbc.sql("""
				SELECT file_id, account_id FROM identity.student_card
				WHERE id = :id AND status <> 'PENDING' AND file_deleted_at IS NULL FOR UPDATE
				""")
			.param("id", deadline.subjectId())
			.query((rs, n) -> new Row(rs.getObject("file_id", UUID.class), rs.getObject("account_id", UUID.class)))
			.optional();
		if (card.isEmpty()) {
			return DeadlineOutcome.OBSOLETE;
		}
		UUID fileId = card.get().fileId();
		this.files.delete(fileId);
		if (this.files.exists(fileId)) {
			throw new IllegalStateException("Student card file " + fileId + " is still on the files volume");
		}
		this.jdbc.sql("UPDATE identity.student_card SET file_deleted_at = :now WHERE id = :id")
			.param("id", deadline.subjectId())
			.param("now", Timestamp.from(this.clock.instant()))
			.update();
		this.auditLog.record(AuditEntry.of(AuditActor.system(), "identity.student_card_file_deleted",
				AuditObject.of("identity.student_card", deadline.subjectId()),
				AuditObject.of("identity.account", card.get().accountId())));
		return DeadlineOutcome.FIRED;
	}

}
