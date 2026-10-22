package vn.vibefix.chat.internal;

import java.sql.Timestamp;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import vn.vibefix.chat.ChatEvidence;

/** {@link ChatEvidence} over the Job Chat's messages. */
@Component
class JobChatEvidence implements ChatEvidence {

	private final JdbcClient jdbc;

	JobChatEvidence(JdbcClient jdbc) {
		this.jdbc = jdbc;
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

}
