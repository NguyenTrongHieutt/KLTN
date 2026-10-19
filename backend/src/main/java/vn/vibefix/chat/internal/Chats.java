package vn.vibefix.chat.internal;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import vn.vibefix.chat.internal.SensitiveContent.Prepared;
import vn.vibefix.files.PreparedFile;
import vn.vibefix.files.StoredFile;
import vn.vibefix.files.StoredFiles;
import vn.vibefix.shared.IdGenerator;

/**
 * The Chats and their messages (FR-46): one Job Chat per Job, opened once; messages page newest
 * first, 50 at a time, and a read marker per participant drives the unread dot.
 */
@Component
class Chats {

	/** Messages per page (UX-DR25). */
	static final int PAGE_SIZE = 50;

	/** The file purpose of an attachment, whose read policy is {@link ChatAttachmentFiles}. */
	static final String ATTACHMENT_PURPOSE = "chat.attachment";

	enum Kind {
		JOB, CREW
	}

	record Chat(UUID id, UUID jobId, UUID productId, Kind kind, Instant openedAt) {
	}

	record Attachment(UUID fileId, String name, String mediaType, long size) {
	}

	record Message(UUID id, UUID chatId, UUID authorId, String body, int maskedCredentials, int maskedPersonal,
			boolean sentDespiteWarning, @Nullable Attachment attachment, Instant sentAt) {
	}

	/** A page of messages, oldest first. */
	record Page(List<Message> items, boolean hasOlder) {
	}

	private static final String MESSAGE_COLUMNS = """
			id, chat_id, author_account_id, body, masked_credentials, masked_personal, sent_despite_warning,
			attachment_file_id, attachment_name, attachment_media_type, attachment_size, sent_at
			""";

	private final JdbcClient jdbc;

	private final StoredFiles files;

	private final IdGenerator ids;

	private final Clock clock;

	Chats(JdbcClient jdbc, StoredFiles files, IdGenerator ids, Clock clock) {
		this.jdbc = jdbc;
		this.files = files;
		this.ids = ids;
		this.clock = clock;
	}

	/** Opens the Job's Chat of {@code kind}; an open one stays as it is. Returns it and whether it is new. */
	@Transactional
	Optional<Chat> open(UUID jobId, UUID productId, Kind kind) {
		int opened = this.jdbc.sql("""
				INSERT INTO chat.chat (id, job_id, product_id, kind, opened_at) VALUES (:id, :job, :product, :kind, :now)
				ON CONFLICT (job_id, kind) DO NOTHING
				""")
			.param("id", this.ids.newId())
			.param("job", jobId)
			.param("product", productId)
			.param("kind", kind.name())
			.param("now", Timestamp.from(this.clock.instant()))
			.update();
		return opened == 1 ? find(jobId, kind) : Optional.empty();
	}

	@Transactional(readOnly = true)
	Optional<Chat> find(UUID jobId, Kind kind) {
		return this.jdbc.sql("SELECT id, job_id, product_id, kind, opened_at FROM chat.chat WHERE job_id = :job AND kind = :kind")
			.param("job", jobId)
			.param("kind", kind.name())
			.query(Chats::chat)
			.optional();
	}

	@Transactional(readOnly = true)
	Optional<Chat> byId(UUID chatId) {
		return this.jdbc.sql("SELECT id, job_id, product_id, kind, opened_at FROM chat.chat WHERE id = :id")
			.param("id", chatId)
			.query(Chats::chat)
			.optional();
	}

	/** The newest {@value #PAGE_SIZE} messages before {@code before} (or of all), oldest first. */
	@Transactional(readOnly = true)
	Page messages(UUID chatId, @Nullable UUID before) {
		String cursor = before == null ? "" : """
				 AND (sent_at, id) < (SELECT sent_at, id FROM chat.message WHERE id = :before AND chat_id = :chat)
				""";
		JdbcClient.StatementSpec query = this.jdbc.sql("SELECT " + MESSAGE_COLUMNS + " FROM chat.message WHERE chat_id = :chat" + cursor
				+ " ORDER BY sent_at DESC, id DESC LIMIT :limit")
			.param("chat", chatId)
			.param("limit", PAGE_SIZE + 1);
		if (before != null) {
			query = query.param("before", before);
		}
		List<Message> newestFirst = new ArrayList<>(query.query(Chats::message).list());
		boolean older = newestFirst.size() > PAGE_SIZE;
		if (older) {
			newestFirst.removeLast();
		}
		return new Page(newestFirst.reversed(), older);
	}

	/** Stores a checked message, with its prepared attachment when there is one. */
	@Transactional
	Message post(Chat chat, UUID authorId, Prepared prepared, @Nullable PreparedFile file) {
		UUID id = this.ids.newId();
		Instant now = this.clock.instant();
		StoredFile stored = file == null ? null : this.files.store(file);
		this.jdbc.sql("""
				INSERT INTO chat.message (id, chat_id, author_account_id, body, masked_credentials, masked_personal,
				    sent_despite_warning, attachment_file_id, attachment_name, attachment_media_type, attachment_size, sent_at)
				VALUES (:id, :chat, :author, :body, :credentials, :personal, :despite, :file, :name, :type, :size, :now)
				""")
			.param("id", id)
			.param("chat", chat.id())
			.param("author", authorId)
			.param("body", prepared.body())
			.param("credentials", prepared.maskedCredentials())
			.param("personal", prepared.maskedPersonal())
			.param("despite", prepared.sentDespiteWarning())
			.param("file", stored == null ? null : stored.id())
			.param("name", stored == null ? null : stored.fileName())
			.param("type", stored == null ? null : stored.mediaType())
			.param("size", stored == null ? null : stored.size())
			.param("now", Timestamp.from(now))
			.update();
		markRead(chat.id(), authorId);
		return new Message(id, chat.id(), authorId, prepared.body(), prepared.maskedCredentials(),
				prepared.maskedPersonal(), prepared.sentDespiteWarning(),
				stored == null ? null : new Attachment(stored.id(), stored.fileName(), stored.mediaType(), stored.size()),
				now);
	}

	/** The reader has seen everything up to now. */
	@Transactional
	void markRead(UUID chatId, UUID accountId) {
		this.jdbc.sql("""
				INSERT INTO chat.read_marker (chat_id, account_id, read_at) VALUES (:chat, :account, :now)
				ON CONFLICT (chat_id, account_id) DO UPDATE SET read_at = excluded.read_at
				""")
			.param("chat", chatId)
			.param("account", accountId)
			.param("now", Timestamp.from(this.clock.instant()))
			.update();
	}

	/** Whether someone else wrote after the reader last read. */
	@Transactional(readOnly = true)
	boolean unread(UUID chatId, UUID accountId) {
		return this.jdbc.sql("""
				SELECT EXISTS (SELECT 1 FROM chat.message m WHERE m.chat_id = :chat AND m.author_account_id <> :account
				  AND m.sent_at > coalesce((SELECT read_at FROM chat.read_marker WHERE chat_id = :chat AND account_id = :account),
				                           '-infinity'::timestamptz))
				""").param("chat", chatId).param("account", accountId).query(Boolean.class).single();
	}

	/** The message carrying the attachment, for its read policy. */
	@Transactional(readOnly = true)
	Optional<Message> byAttachment(UUID fileId) {
		return this.jdbc.sql("SELECT " + MESSAGE_COLUMNS + " FROM chat.message WHERE attachment_file_id = :file")
			.param("file", fileId)
			.query(Chats::message)
			.optional();
	}

	private static Chat chat(ResultSet rs, int n) throws SQLException {
		return new Chat(rs.getObject("id", UUID.class), rs.getObject("job_id", UUID.class),
				rs.getObject("product_id", UUID.class), Kind.valueOf(rs.getString("kind")),
				rs.getTimestamp("opened_at").toInstant());
	}

	private static Message message(ResultSet rs, int n) throws SQLException {
		UUID file = rs.getObject("attachment_file_id", UUID.class);
		return new Message(rs.getObject("id", UUID.class), rs.getObject("chat_id", UUID.class),
				rs.getObject("author_account_id", UUID.class), rs.getString("body"), rs.getInt("masked_credentials"),
				rs.getInt("masked_personal"), rs.getBoolean("sent_despite_warning"),
				file == null ? null
						: new Attachment(file, rs.getString("attachment_name"), rs.getString("attachment_media_type"),
								rs.getLong("attachment_size")),
				rs.getTimestamp("sent_at").toInstant());
	}

}
