package vn.vibefix.chat.internal;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import vn.vibefix.files.StoredFiles;
import vn.vibefix.shared.ErasureBlocker;
import vn.vibefix.shared.PersonalDataParticipant;
import vn.vibefix.shared.PersonalDataSection;

/**
 * {@code chat}'s part in exports, erasure, and Product deletion (AD-24): an account exports the
 * messages it wrote; erasing it deletes them with their attachments, its read markers, and, for an
 * Administrator, its read grants; deleting a Product deletes its Chats. A running Job blocks erasure
 * through {@code job}.
 */
@Component
class ChatPersonalData implements PersonalDataParticipant {

	private final JdbcClient jdbc;

	private final StoredFiles files;

	ChatPersonalData(JdbcClient jdbc, StoredFiles files) {
		this.jdbc = jdbc;
		this.files = files;
	}

	@Override
	public Set<String> tables() {
		return Set.of("chat.chat", "chat.message", "chat.read_marker", "chat.admin_read_grant");
	}

	@Override
	public Optional<PersonalDataSection> export(UUID accountId) {
		List<Map<String, Object>> messages = this.jdbc.sql("""
				SELECT c.job_id, c.kind, m.body, m.attachment_name, m.sent_at
				FROM chat.message m JOIN chat.chat c ON c.id = m.chat_id
				WHERE m.author_account_id = :id ORDER BY m.sent_at, m.id
				""").param("id", accountId).query((rs, n) -> {
			Map<String, Object> message = new LinkedHashMap<>();
			message.put("jobId", rs.getObject("job_id", UUID.class).toString());
			message.put("chat", rs.getString("kind"));
			message.put("body", rs.getString("body"));
			message.put("attachment", rs.getString("attachment_name"));
			message.put("sentAt", rs.getTimestamp("sent_at").toInstant().toString());
			return message;
		}).list();
		if (messages.isEmpty()) {
			return Optional.empty();
		}
		return Optional.of(new PersonalDataSection("chat", Map.of("messages", messages)));
	}

	@Override
	public void eraseAccount(UUID accountId) {
		deleteAttachments("SELECT attachment_file_id FROM chat.message WHERE author_account_id = :id", accountId);
		this.jdbc.sql("DELETE FROM chat.message WHERE author_account_id = :id").param("id", accountId).update();
		this.jdbc.sql("DELETE FROM chat.read_marker WHERE account_id = :id").param("id", accountId).update();
		this.jdbc.sql("DELETE FROM chat.admin_read_grant WHERE admin_account_id = :id").param("id", accountId).update();
	}

	@Override
	public void deleteProduct(UUID productId) {
		String chats = "SELECT id FROM chat.chat WHERE product_id = :id";
		deleteAttachments("SELECT attachment_file_id FROM chat.message WHERE chat_id IN (" + chats + ")", productId);
		this.jdbc.sql("DELETE FROM chat.message WHERE chat_id IN (" + chats + ")").param("id", productId).update();
		this.jdbc.sql("DELETE FROM chat.read_marker WHERE chat_id IN (" + chats + ")").param("id", productId).update();
		this.jdbc.sql("DELETE FROM chat.admin_read_grant WHERE job_id IN (SELECT job_id FROM chat.chat WHERE product_id = :id)")
			.param("id", productId)
			.update();
		this.jdbc.sql("DELETE FROM chat.chat WHERE product_id = :id").param("id", productId).update();
	}

	@Override
	public List<ErasureBlocker> erasureBlockers(UUID accountId) {
		return List.of();
	}

	private void deleteAttachments(String query, UUID id) {
		this.jdbc.sql(query + " AND attachment_file_id IS NOT NULL")
			.param("id", id)
			.query(UUID.class)
			.list()
			.forEach(this.files::delete);
	}

}
