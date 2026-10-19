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
 * Who may read a student card's file (FR-5, AD-20): Administrators only, never its own Student,
 * and only while the file is kept. Each read is audited as {@code identity.student_card_viewed} (FR-63),
 * so ADM-3's "Xem thẻ" leaves a trace of who opened which card and when.
 */
@Component
class StudentCardFiles implements FileReadPolicy {

	private final JdbcClient jdbc;

	private final AuditLog auditLog;

	StudentCardFiles(JdbcClient jdbc, AuditLog auditLog) {
		this.jdbc = jdbc;
		this.auditLog = auditLog;
	}

	@Override
	public String purpose() {
		return StudentCards.PURPOSE;
	}

	@Override
	@Transactional
	public boolean mayRead(UUID fileId, Caller caller) {
		if (!caller.hasRole(Role.ADMINISTRATOR)) {
			return false;
		}
		record Card(UUID id, UUID accountId) {
		}
		Optional<Card> card = this.jdbc.sql("""
				SELECT id, account_id FROM identity.student_card WHERE file_id = :file AND file_deleted_at IS NULL
				""")
			.param("file", fileId)
			.query((rs, n) -> new Card(rs.getObject("id", UUID.class), rs.getObject("account_id", UUID.class)))
			.optional();
		card.ifPresent(found -> this.auditLog.record(AuditEntry.of(AuditActor.user(caller.requireAccountId()),
				"identity.student_card_viewed", AuditObject.of("identity.student_card", found.id()),
				AuditObject.of("identity.account", found.accountId()))));
		return card.isPresent();
	}

}
