package vn.vibefix.chat.internal;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import vn.vibefix.audit.AuditActor;
import vn.vibefix.audit.AuditEntry;
import vn.vibefix.audit.AuditLog;
import vn.vibefix.audit.AuditObject;
import vn.vibefix.chat.ChatEvidence;
import vn.vibefix.shared.AdminReadScope;
import vn.vibefix.shared.ApiException;
import vn.vibefix.shared.ErrorCode;

/** {@link ChatEvidence} over the messages of a Job's two Chats. */
@Component
class JobChatEvidence implements ChatEvidence {

	/** At most this many messages on one view of a Crew Chat's interval, the newest of them. */
	private static final int MOST = 1_000;

	private static final String IN_INTERVAL = "(CAST(:from AS TIMESTAMPTZ) IS NULL OR m.sent_at >= :from) AND m.sent_at <= :to";

	private final JdbcClient jdbc;

	private final ChatGrants grants;

	private final AuditLog auditLog;

	private final Clock clock;

	JobChatEvidence(JdbcClient jdbc, ChatGrants grants, AuditLog auditLog, Clock clock) {
		this.jdbc = jdbc;
		this.grants = grants;
		this.auditLog = auditLog;
		this.clock = clock;
	}

	@Override
	@Transactional(readOnly = true)
	public ChatExtent jobChat(UUID jobId) {
		int count = this.jdbc.sql("""
				SELECT count(*) FROM chat.message m JOIN chat.chat c ON c.id = m.chat_id WHERE c.job_id = :job AND c.kind = 'JOB'
				""").param("job", jobId).query(Integer.class).single();
		return this.jdbc.sql("""
				SELECT m.id, m.sent_at FROM chat.message m JOIN chat.chat c ON c.id = m.chat_id
				WHERE c.job_id = :job AND c.kind = 'JOB' ORDER BY m.sent_at DESC, m.id DESC LIMIT 1
				""").param("job", jobId).query((rs, n) -> {
			Timestamp sent = rs.getTimestamp("sent_at");
			return new ChatExtent(count, rs.getObject("id", UUID.class), sent.toInstant());
		}).optional().orElse(new ChatExtent(count, null, null));
	}

	@Override
	@Transactional
	public CrewMessages crewChatUnderGrant(UUID adminId, UUID jobId, @Nullable Instant from, Instant to) {
		UUID chat = this.jdbc.sql("SELECT id FROM chat.chat WHERE job_id = :job AND kind = 'CREW'")
			.param("job", jobId)
			.query(UUID.class)
			.optional()
			.orElse(null);
		if (chat == null) {
			return new CrewMessages(List.of(), 0);
		}
		if (!this.grants.permits(adminId, jobId, AdminReadScope.CREW_CHAT, chat, List.of(), this.clock.instant())) {
			throw new ApiException(ErrorCode.NOT_FOUND);
		}
		this.auditLog.record(AuditEntry.of(AuditActor.user(adminId), "chat.admin_read", AuditObject.of("chat.chat", chat))
			.forJob(jobId));
		List<CrewMessage> newest = this.jdbc.sql("""
				SELECT * FROM (SELECT m.id, m.author_account_id, m.body, m.attachment_name, m.sent_at FROM chat.message m
				    WHERE m.chat_id = :chat AND """ + IN_INTERVAL + """
				    ORDER BY m.sent_at DESC, m.id DESC LIMIT :most) newest
				ORDER BY sent_at, id
				""")
			.param("chat", chat)
			.param("from", from == null ? null : Timestamp.from(from), java.sql.Types.TIMESTAMP)
			.param("to", Timestamp.from(to))
			.param("most", MOST)
			.query((rs, n) -> new CrewMessage(rs.getObject("id", UUID.class), rs.getObject("author_account_id", UUID.class),
					rs.getString("body"), rs.getString("attachment_name"), rs.getTimestamp("sent_at").toInstant()))
			.list();
		return new CrewMessages(newest, crewChat(jobId, from, to).messageCount());
	}

	@Override
	@Transactional(readOnly = true)
	public ChatExtent crewChat(UUID jobId, @Nullable Instant from, Instant to) {
		Timestamp start = from == null ? null : Timestamp.from(from);
		int count = this.jdbc.sql("""
				SELECT count(*) FROM chat.message m JOIN chat.chat c ON c.id = m.chat_id
				WHERE c.job_id = :job AND c.kind = 'CREW' AND """ + IN_INTERVAL)
			.param("job", jobId)
			.param("from", start, java.sql.Types.TIMESTAMP)
			.param("to", Timestamp.from(to))
			.query(Integer.class)
			.single();
		return this.jdbc.sql("""
				SELECT m.id, m.sent_at FROM chat.message m JOIN chat.chat c ON c.id = m.chat_id
				WHERE c.job_id = :job AND c.kind = 'CREW' AND """ + IN_INTERVAL + """
				 ORDER BY m.sent_at DESC, m.id DESC LIMIT 1
				""")
			.param("job", jobId)
			.param("from", start, java.sql.Types.TIMESTAMP)
			.param("to", Timestamp.from(to))
			.query((rs, n) -> new ChatExtent(count, rs.getObject("id", UUID.class), rs.getTimestamp("sent_at").toInstant()))
			.optional()
			.orElse(new ChatExtent(count, null, null));
	}

}
