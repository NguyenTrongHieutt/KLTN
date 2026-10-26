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
 * {@value #KEY} (AD-24): 12 months after a rejection {@code [ASSUMPTION]}, the application's own fields
 * (the LinkedIn link, the other link, the document, the notices) are deleted; the decision record in
 * {@code identity.mentor_decision} stays. The display name, experience, years, and Skills are the
 * person's own profile since Story 19.3, and are kept until the account is erased. A resubmission or
 * a later decision leaves it {@code OBSOLETE}.
 */
@Component
class MentorApplicationPurge implements DeadlineHandler {

	static final String KEY = "identity.mentor_application_purge";

	static final DeadlineKind KIND = new DeadlineKind(KEY, false, false);

	private final JdbcClient jdbc;

	private final StoredFiles files;

	private final AuditLog auditLog;

	MentorApplicationPurge(JdbcClient jdbc, StoredFiles files, AuditLog auditLog) {
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
		boolean rejected = MentorDecisions.currentDecision(this.jdbc, account, deadline.generation())
			.filter("REJECTED"::equals)
			.isPresent();
		if (!rejected) {
			return DeadlineOutcome.OBSOLETE;
		}
		Optional<UUID> document = this.jdbc.sql("SELECT document_file_id FROM identity.mentor_application WHERE account_id = :id AND document_file_id IS NOT NULL")
			.param("id", account)
			.query(UUID.class)
			.optional();
		document.ifPresent(this.files::delete);
		this.jdbc.sql("DELETE FROM identity.mentor_application WHERE account_id = :id").param("id", account).update();
		this.auditLog.record(AuditEntry.of(AuditActor.system(), "identity.mentor_application_purged",
				AuditObject.of("identity.account", account)));
		return DeadlineOutcome.FIRED;
	}

}
