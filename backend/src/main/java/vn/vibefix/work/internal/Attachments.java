package vn.vibefix.work.internal;

import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import vn.vibefix.files.FileKind;
import vn.vibefix.files.FileUpload;
import vn.vibefix.files.PreparedFile;
import vn.vibefix.files.StoredFile;
import vn.vibefix.files.StoredFiles;
import vn.vibefix.job.JobUploads;
import vn.vibefix.shared.ApiException;
import vn.vibefix.shared.ErrorCode;
import vn.vibefix.shared.IdGenerator;
import vn.vibefix.work.internal.Boards.Item;
import vn.vibefix.work.internal.Boards.Status;
import vn.vibefix.work.internal.WorkAccess.BoardViewer;
import vn.vibefix.work.internal.WorkTextCheck.MatchView;
import vn.vibefix.work.internal.WorkTextCheck.Resolution;

/**
 * A Work Item's Attachments (FR-76, NFR-3, AD-21, AD-34, AD-39): files that {@code files} checks and
 * stores, counted with the Job's screenshots toward its 200 MB under the Board lock, and {@code https}
 * links, each with its author and time and an {@code ATTACHED} history entry. A plain-text file's
 * content, a link's label, and its URL pass the FR-47 check; a URL with a match is refused even after
 * the warning, since a masked URL leads nowhere. Only the author removes one, and not once the Work Item
 * is Done: the row is marked removed with a {@code DETACHED} entry, and the file stays until the Job's
 * data is deleted (NFR-10).
 */
@Component
class Attachments {

	static final String PURPOSE = "work.attachment";

	/** Every kind NFR-3 allows: images, PDF, plain text, and archives. */
	static final Set<FileKind> KINDS = Set.of(FileKind.IMAGE, FileKind.PDF, FileKind.TEXT, FileKind.ARCHIVE);

	static final int MAX_URL = 2000;

	static final int MAX_LABEL = 200;

	enum Kind {
		FILE, LINK
	}

	/** One Attachment; a file's columns or a link's are set by its kind. */
	record Attachment(UUID id, UUID itemId, UUID jobId, Kind kind, @Nullable UUID fileId, @Nullable String fileName,
			@Nullable String mediaType, @Nullable Long size, @Nullable String url, @Nullable String label,
			@Nullable UUID authorId, Instant addedAt, @Nullable Instant removedAt) {

		/** Whether it was removed at or before {@code at}. */
		boolean removedBy(Instant at) {
			return this.removedAt != null && !this.removedAt.isAfter(at);
		}

		/** What the list shows: a file's name, else the link's label, else its domain. */
		String name() {
			if (this.kind == Kind.FILE) {
				return this.fileName == null ? "" : this.fileName;
			}
			return this.label != null && !this.label.isBlank() ? this.label : domain(this.url);
		}

	}

	private static final String COLUMNS = """
			id, work_item_id, job_id, kind, file_id, file_name, media_type, size_bytes, url, label, author_account_id,
			added_at, removed_at
			""";

	private final Boards boards;

	private final JdbcClient jdbc;

	private final StoredFiles files;

	private final JobUploads uploads;

	private final WorkTextCheck check;

	private final IdGenerator ids;

	private final Clock clock;

	Attachments(Boards boards, JdbcClient jdbc, StoredFiles files, JobUploads uploads, WorkTextCheck check, IdGenerator ids,
			Clock clock) {
		this.boards = boards;
		this.jdbc = jdbc;
		this.files = files;
		this.uploads = uploads;
		this.check = check;
		this.ids = ids;
		this.clock = clock;
	}

	/**
	 * Checks a file outside any transaction (AD-21): {@code files}' type, size, and hourly quota, and
	 * for plain text the FR-47 check on its content, whose masked text is uploaded under {@code MASK}.
	 * @throws ApiException {@code GUARD_FAILED SENSITIVE_CONTENT} with the matches under {@code CHECK}
	 */
	PreparedFile prepare(BoardViewer writer, String fileName, byte[] content, Resolution resolution) {
		PreparedFile prepared = this.files.prepare(new FileUpload(PURPOSE, writer.accountId(), fileName, content, KINDS));
		if (prepared.kind() != FileKind.TEXT) {
			return prepared;
		}
		List<MatchView> matches = new ArrayList<>();
		String masked = this.check.maskedLong(new String(content, StandardCharsets.UTF_8), matches);
		if (matches.isEmpty()) {
			return prepared;
		}
		WorkTextCheck.requireResolved(matches, resolution);
		return this.files.prepare(new FileUpload(PURPOSE, writer.accountId(), fileName,
				masked.getBytes(StandardCharsets.UTF_8), KINDS));
	}

	/**
	 * Stores a checked file on the Work Item, within the Job's 200 MB (AD-34).
	 * @throws ApiException {@code NOT_FOUND}; {@code VALIDATION JOB_FILE_QUOTA}
	 */
	@Transactional
	Attachment attachFile(BoardViewer writer, UUID itemId, PreparedFile file) {
		UUID jobId = writer.board().jobId();
		this.boards.lockBoard(jobId);
		requireItem(writer, itemId);
		long used = this.jdbc.sql("SELECT coalesce(sum(size_bytes), 0) FROM work.attachment WHERE job_id = :job")
			.param("job", jobId)
			.query(Long.class)
			.single() + this.uploads.screenshotBytes(jobId);
		if (used + file.size() > JobUploads.QUOTA_BYTES) {
			throw new ApiException(ErrorCode.VALIDATION, WorkReason.JOB_FILE_QUOTA);
		}
		StoredFile stored = this.files.store(file);
		UUID id = this.ids.newId();
		this.jdbc.sql("""
				INSERT INTO work.attachment (id, work_item_id, job_id, kind, file_id, file_name, media_type, size_bytes,
				    author_account_id, added_at)
				VALUES (:id, :item, :job, 'FILE', :file, :name, :type, :size, :author, :now)
				""")
			.param("id", id)
			.param("item", itemId)
			.param("job", jobId)
			.param("file", stored.id())
			.param("name", stored.fileName())
			.param("type", stored.mediaType())
			.param("size", stored.size())
			.param("author", writer.accountId())
			.param("now", Timestamp.from(this.clock.instant()))
			.update();
		this.boards.attachmentEntry(itemId, jobId, writer.accountId(), "ATTACHED", id);
		return find(id).orElseThrow();
	}

	/**
	 * Attaches an {@code https} link with an optional label, both FR-47 checked.
	 * @throws ApiException {@code NOT_FOUND}; {@code VALIDATION LINK_INVALID}; {@code GUARD_FAILED
	 * LINK_SENSITIVE} when the URL holds a match; {@code GUARD_FAILED SENSITIVE_CONTENT} for the label
	 * under {@code CHECK}
	 */
	@Transactional
	Attachment attachLink(BoardViewer writer, UUID itemId, String rawUrl, @Nullable String rawLabel,
			Resolution resolution) {
		requireItem(writer, itemId);
		String url = validLink(rawUrl);
		List<MatchView> inUrl = new ArrayList<>();
		this.check.masked(url, inUrl);
		if (!inUrl.isEmpty()) {
			throw new ApiException(ErrorCode.GUARD_FAILED, WorkReason.LINK_SENSITIVE, Map.of("matches", inUrl));
		}
		String label = rawLabel == null ? "" : rawLabel.strip();
		if (label.length() > MAX_LABEL) {
			throw new ApiException(ErrorCode.VALIDATION);
		}
		List<MatchView> matches = new ArrayList<>();
		String masked = this.check.masked(label, matches);
		WorkTextCheck.requireResolved(matches, resolution);
		UUID id = this.ids.newId();
		this.jdbc.sql("""
				INSERT INTO work.attachment (id, work_item_id, job_id, kind, url, label, author_account_id, added_at)
				VALUES (:id, :item, :job, 'LINK', :url, :label, :author, :now)
				""")
			.param("id", id)
			.param("item", itemId)
			.param("job", writer.board().jobId())
			.param("url", url)
			.param("label", masked.isEmpty() ? null : masked)
			.param("author", writer.accountId())
			.param("now", Timestamp.from(this.clock.instant()))
			.update();
		this.boards.attachmentEntry(itemId, writer.board().jobId(), writer.accountId(), "ATTACHED", id);
		return find(id).orElseThrow();
	}

	/**
	 * "Gỡ": the author marks their Attachment removed while the Work Item is not Done.
	 * @throws ApiException {@code NOT_FOUND}; {@code FORBIDDEN NOT_AUTHOR}; {@code GUARD_FAILED WORK_ITEM_DONE}
	 */
	@Transactional
	void remove(BoardViewer writer, UUID itemId, UUID attachmentId) {
		Item item = this.boards.lockItem(itemId)
			.filter(found -> found.jobId().equals(writer.board().jobId()))
			.orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
		Attachment attachment = find(attachmentId)
			.filter(found -> found.itemId().equals(itemId) && found.removedAt() == null)
			.orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
		if (!writer.accountId().equals(attachment.authorId())) {
			throw new ApiException(ErrorCode.FORBIDDEN, WorkReason.NOT_AUTHOR);
		}
		if (item.status() == Status.DONE) {
			throw new ApiException(ErrorCode.GUARD_FAILED, WorkReason.WORK_ITEM_DONE);
		}
		this.jdbc.sql("UPDATE work.attachment SET removed_at = :now, removed_by = :by WHERE id = :id")
			.param("id", attachmentId)
			.param("by", writer.accountId())
			.param("now", Timestamp.from(this.clock.instant()))
			.update();
		this.boards.attachmentEntry(itemId, item.jobId(), writer.accountId(), "DETACHED", attachmentId);
	}

	/** Every Attachment of the Work Item, removed ones included, oldest first. */
	@Transactional(readOnly = true)
	List<Attachment> of(UUID itemId) {
		return this.jdbc.sql("SELECT " + COLUMNS + " FROM work.attachment WHERE work_item_id = :item ORDER BY seq")
			.param("item", itemId)
			.query(Attachments::attachment)
			.list();
	}

	@Transactional(readOnly = true)
	Optional<Attachment> byFile(UUID fileId) {
		return this.jdbc.sql("SELECT " + COLUMNS + " FROM work.attachment WHERE file_id = :file")
			.param("file", fileId)
			.query(Attachments::attachment)
			.optional();
	}

	private Optional<Attachment> find(UUID id) {
		return this.jdbc.sql("SELECT " + COLUMNS + " FROM work.attachment WHERE id = :id")
			.param("id", id)
			.query(Attachments::attachment)
			.optional();
	}

	private void requireItem(BoardViewer writer, UUID itemId) {
		this.boards.item(itemId)
			.filter(found -> found.jobId().equals(writer.board().jobId()))
			.orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
	}

	/**
	 * The link as stored: {@code https}, with a host, without user info, spaces, or control characters,
	 * and at most {@value #MAX_URL} characters.
	 * @throws ApiException {@code VALIDATION LINK_INVALID}
	 */
	static String validLink(String raw) {
		String url = raw.strip();
		boolean plain = url.chars().noneMatch(c -> c <= 0x20 || c == 0x7f);
		URI uri = null;
		if (plain && url.length() <= MAX_URL) {
			try {
				uri = new URI(url);
			}
			catch (URISyntaxException ex) {
				uri = null;
			}
		}
		if (uri == null || !"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null || uri.getHost().isBlank()
				|| uri.getRawUserInfo() != null) {
			throw new ApiException(ErrorCode.VALIDATION, WorkReason.LINK_INVALID);
		}
		return url;
	}

	/** The link's host in lower case, shown as its domain; empty if it cannot be read. */
	static String domain(@Nullable String url) {
		if (url == null) {
			return "";
		}
		try {
			String host = new URI(url).getHost();
			return host == null ? "" : host.toLowerCase(Locale.ROOT);
		}
		catch (URISyntaxException ex) {
			return "";
		}
	}

	private static Attachment attachment(ResultSet rs, int n) throws SQLException {
		Timestamp removed = rs.getTimestamp("removed_at");
		return new Attachment(rs.getObject("id", UUID.class), rs.getObject("work_item_id", UUID.class),
				rs.getObject("job_id", UUID.class), Kind.valueOf(rs.getString("kind")), rs.getObject("file_id", UUID.class),
				rs.getString("file_name"), rs.getString("media_type"), rs.getObject("size_bytes", Long.class),
				rs.getString("url"), rs.getString("label"), rs.getObject("author_account_id", UUID.class),
				rs.getTimestamp("added_at").toInstant(), removed == null ? null : removed.toInstant());
	}

}
