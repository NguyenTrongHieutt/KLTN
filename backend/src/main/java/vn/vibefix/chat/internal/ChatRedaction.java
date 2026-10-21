package vn.vibefix.chat.internal;

import java.sql.Timestamp;
import java.time.Clock;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import vn.vibefix.scanning.SensitiveText;
import vn.vibefix.scanning.SensitiveText.Kind;
import vn.vibefix.scanning.SensitiveText.Result;

/**
 * FR-47 and FR-66 at the Job's end: once it is Closed or Cancelled, every message of its Job Chat and
 * Crew Chat that was sent as written despite the warning ("Vẫn gửi") is checked again with the
 * {@code scanning} rules and stored masked, keeping no original. Each message is checked once.
 */
@Component
class ChatRedaction {

	private record Sent(UUID id, String body) {
	}

	private final JdbcClient jdbc;

	private final SensitiveText check;

	private final Clock clock;

	ChatRedaction(JdbcClient jdbc, SensitiveText check, Clock clock) {
		this.jdbc = jdbc;
		this.check = check;
		this.clock = clock;
	}

	/** @return how many messages were stored masked */
	@Transactional
	int redact(UUID jobId) {
		Timestamp now = Timestamp.from(this.clock.instant());
		int masked = 0;
		for (Sent sent : this.jdbc.sql("""
				SELECT m.id, m.body FROM chat.message m JOIN chat.chat c ON c.id = m.chat_id
				WHERE c.job_id = :job AND m.sent_despite_warning AND m.redacted_at IS NULL
				""").param("job", jobId).query((rs, n) -> new Sent(rs.getObject("id", UUID.class), rs.getString("body"))).list()) {
			Result result = this.check.check(sent.body());
			if (!result.found()) {
				this.jdbc.sql("UPDATE chat.message SET redacted_at = :now WHERE id = :id")
					.param("now", now)
					.param("id", sent.id())
					.update();
				continue;
			}
			this.jdbc.sql("""
					UPDATE chat.message SET body = :body, masked_credentials = masked_credentials + :credentials,
					    masked_personal = masked_personal + :personal, redacted_at = :now
					WHERE id = :id
					""")
				.param("body", result.maskedText())
				.param("credentials", (int) result.count(Kind.CREDENTIAL))
				.param("personal", (int) result.count(Kind.PERSONAL_DATA))
				.param("now", now)
				.param("id", sent.id())
				.update();
			masked++;
		}
		return masked;
	}

}
