package vn.vibefix.identity.internal;

import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import vn.vibefix.audit.AuditActor;
import vn.vibefix.audit.AuditEntry;
import vn.vibefix.audit.AuditLog;
import vn.vibefix.audit.AuditObject;
import vn.vibefix.files.FileReadPolicy;
import vn.vibefix.shared.Caller;
import vn.vibefix.shared.Role;

/**
 * Who may read a Mentor application's document (FR-74, AD-20): Administrators only, never its
 * applicant, who sees its name. Each read is audited as {@code identity.mentor_document_viewed} (FR-63).
 */
@Component
class MentorDocumentFiles implements FileReadPolicy {

	private final JdbcClient jdbc;

	private final AuditLog auditLog;

	MentorDocumentFiles(JdbcClient jdbc, AuditLog auditLog) {
		this.jdbc = jdbc;
		this.auditLog = auditLog;
	}

	@Override
	public String purpose() {
		return MentorApplications.DOCUMENT_PURPOSE;
	}

	@Override
	@Transactional
	public boolean mayRead(UUID fileId, Caller caller) {
		if (!caller.hasRole(Role.ADMINISTRATOR)) {
			return false;
		}
		Optional<UUID> applicant = this.jdbc.sql("""
				SELECT account_id FROM identity.mentor_application WHERE document_file_id = :file
				""").param("file", fileId).query(UUID.class).optional();
		applicant.ifPresent(account -> this.auditLog.record(AuditEntry.of(AuditActor.user(caller.requireAccountId()),
				"identity.mentor_document_viewed", AuditObject.of("identity.account", account))));
		return applicant.isPresent();
	}

}
