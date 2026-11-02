package vn.vibefix.chat.internal;

import java.sql.Timestamp;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

import vn.vibefix.files.StoredFiles;
import vn.vibefix.job.MilestoneSplitFinalized;
import vn.vibefix.job.MilestoneSplits;
import vn.vibefix.shared.ErasureBlocker;
import vn.vibefix.shared.PersonalDataParticipant;
import vn.vibefix.shared.PersonalDataSection;

/**
 * {@code chat}'s part in exports, erasure, and Product deletion (AD-24): an account exports the
 * messages it wrote; erasing it deletes them with their attachments, its read markers, and, for an
 * Administrator, its read grants; deleting a Product deletes its Chats. A running Job blocks erasure
 * through {@code job}.
 * <p>
 * While a Milestone Split of a Job is not final, its Crew Chat stays as evidence a Split Resolution reads (AD-24, A-01):
 * the Job Chat goes, a row in {@code chat.split_deferral} says what waits, and the listener on
 * {@link MilestoneSplitFinalized} finishes it once the Job has no open split.
 */
@Component
class ChatPersonalData implements PersonalDataParticipant {

	private final JdbcClient jdbc;

	private final StoredFiles files;

	private final MilestoneSplits splits;

	private final Clock clock;

	ChatPersonalData(JdbcClient jdbc, StoredFiles files, MilestoneSplits splits, Clock clock) {
		this.jdbc = jdbc;
		this.files = files;
		this.splits = splits;
		this.clock = clock;
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
		deleteOrDefer(productId, "DELETE");
	}

	/** NFR-10: the Job Chat and the Crew Chat go with their files, as with the Product's deletion. */
	@Override
	public void retireJobData(UUID productId) {
		deleteOrDefer(productId, "RETIRE");
	}

	/** Every Chat of the Product goes, but the Crew Chat of a Job with an open split, which waits. */
	private void deleteOrDefer(UUID productId, String waiting) {
		Set<UUID> open = this.splits.openSplitJobs(productId);
		open.forEach(job -> defer(job, productId, waiting));
		this.jdbc.sql("SELECT DISTINCT job_id FROM chat.chat WHERE product_id = :id")
			.param("id", productId)
			.query(UUID.class)
			.list()
			.forEach(job -> {
				if (open.contains(job)) {
					deleteChats("job_id = :id AND kind = 'JOB'", job);
				}
				else {
					deleteAllChats(job);
				}
			});
	}

	/** The Job's last split is final: what waited goes. A replay finds no deferral. */
	@ApplicationModuleListener
	void onMilestoneSplitFinalized(MilestoneSplitFinalized event) {
		boolean deferred = this.jdbc.sql("SELECT job_id FROM chat.split_deferral WHERE job_id = :job FOR UPDATE")
			.param("job", event.jobId())
			.query(UUID.class)
			.optional()
			.isPresent();
		if (!deferred || this.splits.hasOpenSplit(event.jobId())) {
			return;
		}
		deleteAllChats(event.jobId());
		this.jdbc.sql("DELETE FROM chat.split_deferral WHERE job_id = :job").param("job", event.jobId()).update();
	}

	private void deleteAllChats(UUID jobId) {
		deleteChats("job_id = :id", jobId);
		this.jdbc.sql("DELETE FROM chat.admin_read_grant WHERE job_id = :id").param("id", jobId).update();
	}

	/** The Chats {@code where} finds by {@code :id}, with their messages, attachments, and read markers. */
	private void deleteChats(String where, UUID id) {
		String chats = "SELECT id FROM chat.chat WHERE " + where;
		deleteAttachments("SELECT attachment_file_id FROM chat.message WHERE chat_id IN (" + chats + ")", id);
		this.jdbc.sql("DELETE FROM chat.message WHERE chat_id IN (" + chats + ")").param("id", id).update();
		this.jdbc.sql("DELETE FROM chat.read_marker WHERE chat_id IN (" + chats + ")").param("id", id).update();
		this.jdbc.sql("DELETE FROM chat.chat WHERE " + where).param("id", id).update();
	}

	/** Records what waits for the Job's open split; a deletion outranks a retirement. */
	private void defer(UUID jobId, UUID productId, String waiting) {
		this.jdbc.sql("""
				INSERT INTO chat.split_deferral (job_id, product_id, waiting, deferred_at) VALUES (:job, :product, :waiting, :now)
				ON CONFLICT (job_id) DO UPDATE SET waiting = CASE WHEN EXCLUDED.waiting = 'DELETE' THEN 'DELETE'
				    ELSE chat.split_deferral.waiting END
				""")
			.param("job", jobId)
			.param("product", productId)
			.param("waiting", waiting)
			.param("now", Timestamp.from(this.clock.instant()))
			.update();
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
