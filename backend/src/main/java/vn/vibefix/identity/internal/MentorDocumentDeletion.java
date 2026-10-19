package vn.vibefix.identity.internal;

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
 * {@value #KEY} (FR-74, AD-7): 30 days after a decision, the application's document is deleted and
 * the deletion checked; a file still there throws, so the poller tries again. A resubmission or a
 * later decision leaves it {@code OBSOLETE}, and so does a document already removed.
 */
@Component
class MentorDocumentDeletion implements DeadlineHandler {

	static final String KEY = "identity.mentor_document_deletion";

	static final DeadlineKind KIND = new DeadlineKind(KEY, false, false);

	private final JdbcClient jdbc;

	private final StoredFiles files;

	private final AuditLog auditLog;

	MentorDocumentDeletion(JdbcClient jdbc, StoredFiles files, AuditLog auditLog) {
		this.jdbc = jdbc;
		this.files = files;
		this.auditLog = auditLog;
	}

	@Override
	public DeadlineKind kind() {
		return KIND;
	}

	@Override
	public DeadlineOutcome handle(DueDeadline deadline) {
		UUID account = deadline.subjectId();
		if (MentorDecisions.currentDecision(this.jdbc, account, deadline.generation()).isEmpty()) {
			return DeadlineOutcome.OBSOLETE;
		}
		Optional<UUID> document = this.jdbc.sql("SELECT document_file_id FROM identity.mentor_application WHERE account_id = :id AND document_file_id IS NOT NULL")
			.param("id", account)
			.query(UUID.class)
			.optional();
		if (document.isEmpty()) {
			return DeadlineOutcome.OBSOLETE;
		}
		this.files.delete(document.get());
		if (this.files.exists(document.get())) {
			throw new IllegalStateException("Mentor document " + document.get() + " is still on the files volume");
		}
		this.jdbc.sql("""
				UPDATE identity.mentor_application SET document_file_id = NULL, document_name = NULL,
				    document_media_type = NULL, document_size = NULL
				WHERE account_id = :id
				""").param("id", account).update();
		this.auditLog.record(AuditEntry.of(AuditActor.system(), "identity.mentor_document_deleted",
				AuditObject.of("identity.account", account)));
		return DeadlineOutcome.FIRED;
	}

}
