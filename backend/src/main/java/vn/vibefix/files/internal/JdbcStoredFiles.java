package vn.vibefix.files.internal;

import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;
import vn.vibefix.files.FileKind;
import vn.vibefix.files.FileUpload;
import vn.vibefix.files.PreparedFile;
import vn.vibefix.files.StoredFile;
import vn.vibefix.files.StoredFiles;
import vn.vibefix.shared.ApiException;
import vn.vibefix.shared.ErrorCode;
import vn.vibefix.shared.IdGenerator;
import vn.vibefix.shared.RateLimitedException;
import vn.vibefix.shared.SandboxJobException;
import vn.vibefix.shared.SandboxJobs;

/**
 * {@link StoredFiles} on {@code files.stored_file} and the {@link FileStore} (AD-21, AD-34). The
 * hourly quota counts every file the account stored in the last hour, deleted ones too; Retry-After
 * is when the oldest of them leaves the hour.
 */
@Component
class JdbcStoredFiles implements StoredFiles {

	private static final Duration QUOTA_WINDOW = Duration.ofHours(1);

	/** A checked upload, with the bytes to store; only {@link #prepare} makes one. */
	private record Prepared(String purpose, UUID accountId, String fileName, String mediaType, FileKind kind,
			byte[] content, String sha256) implements PreparedFile {

		@Override
		public long size() {
			return this.content.length;
		}

	}

	private final JdbcClient jdbc;

	private final FileStore store;

	private final SandboxJobs sandbox;

	private final FilesProperties properties;

	private final IdGenerator ids;

	private final Clock clock;

	JdbcStoredFiles(JdbcClient jdbc, FileStore store, SandboxJobs sandbox, FilesProperties properties, IdGenerator ids,
			Clock clock) {
		this.jdbc = jdbc;
		this.store = store;
		this.sandbox = sandbox;
		this.properties = properties;
		this.ids = ids;
		this.clock = clock;
	}

	@Override
	public PreparedFile prepare(FileUpload upload) {
		requireQuota(upload.accountId(), this.clock.instant());
		byte[] content = upload.content();
		requireSize(content, this.properties.maxSize().toBytes());
		FileTypes.Detected detected = FileTypes.detect(content)
			.filter(type -> upload.allowed().contains(type.kind()))
			.orElseThrow(() -> new ApiException(ErrorCode.VALIDATION, FilesReason.FILE_TYPE_NOT_ALLOWED));
		String mediaType = detected.mediaType();
		String extension = detected.extension();
		if (detected.kind() == FileKind.IMAGE && detected.imageFormat() != null) {
			JsonNode output = reencode(content, detected.imageFormat());
			content = Base64.getDecoder().decode(output.path("content").asString());
			mediaType = output.path("mediaType").asString();
			extension = output.path("extension").asString();
			// A GIF or a lightly compressed JPEG can grow when encoded again; what is stored stays within the limit.
			requireSize(content, this.properties.maxSize().toBytes());
		}
		return new Prepared(upload.purpose(), upload.accountId(), FileNames.normalize(upload.fileName(), extension),
				mediaType, detected.kind(), content, sha256(content));
	}

	@Override
	@Transactional(propagation = Propagation.MANDATORY)
	public StoredFile store(PreparedFile file) {
		if (!(file instanceof Prepared prepared)) {
			throw new IllegalArgumentException("Only StoredFiles.prepare makes a file to store");
		}
		this.jdbc.sql("SELECT pg_advisory_xact_lock(hashtextextended(:key, 0))")
			.param("key", "files.upload:" + prepared.accountId())
			.query((rs, n) -> 1)
			.single();
		Instant now = this.clock.instant().truncatedTo(ChronoUnit.MICROS);
		requireQuota(prepared.accountId(), now);
		UUID id = this.ids.newId();
		this.store.write(id, prepared.content());
		TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
			@Override
			public void afterCompletion(int status) {
				if (status != STATUS_COMMITTED) {
					JdbcStoredFiles.this.store.delete(id);
				}
			}
		});
		this.jdbc.sql("""
				INSERT INTO files.stored_file (id, purpose, account_id, file_name, media_type, kind, size_bytes, sha256, stored_at)
				VALUES (:id, :purpose, :account, :name, :type, :kind, :size, :sha256, :now)
				""")
			.param("id", id)
			.param("purpose", prepared.purpose())
			.param("account", prepared.accountId())
			.param("name", prepared.fileName())
			.param("type", prepared.mediaType())
			.param("kind", prepared.kind().name())
			.param("size", prepared.size())
			.param("sha256", prepared.sha256())
			.param("now", Timestamp.from(now))
			.update();
		return new StoredFile(id, prepared.purpose(), prepared.accountId(), prepared.fileName(), prepared.mediaType(),
				prepared.kind(), prepared.size(), now);
	}

	@Override
	@Transactional(propagation = Propagation.MANDATORY)
	public StoredFile storeArchive(String purpose, UUID accountId, String fileName, byte[] content) {
		Instant now = this.clock.instant().truncatedTo(ChronoUnit.MICROS);
		UUID id = this.ids.newId();
		this.store.write(id, content);
		TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
			@Override
			public void afterCompletion(int status) {
				if (status != STATUS_COMMITTED) {
					JdbcStoredFiles.this.store.delete(id);
				}
			}
		});
		String name = FileNames.normalize(fileName, "zip");
		this.jdbc.sql("""
				INSERT INTO files.stored_file (id, purpose, account_id, file_name, media_type, kind, size_bytes, sha256, stored_at)
				VALUES (:id, :purpose, :account, :name, 'application/zip', 'ARCHIVE', :size, :sha256, :now)
				""")
			.param("id", id)
			.param("purpose", purpose)
			.param("account", accountId)
			.param("name", name)
			.param("size", (long) content.length)
			.param("sha256", sha256(content))
			.param("now", Timestamp.from(now))
			.update();
		return new StoredFile(id, purpose, accountId, name, "application/zip", FileKind.ARCHIVE, content.length, now);
	}

	@Override
	public Optional<StoredFile> find(UUID fileId) {
		return this.jdbc.sql("""
				SELECT id, purpose, account_id, file_name, media_type, kind, size_bytes, stored_at FROM files.stored_file
				WHERE id = :id AND deleted_at IS NULL
				""").param("id", fileId).query((rs, n) -> storedFile(rs)).optional();
	}

	@Override
	public void delete(UUID fileId) {
		// The bytes go first: a rollback after this may leave the row behind, never the bytes.
		this.store.delete(fileId);
		this.jdbc.sql("UPDATE files.stored_file SET deleted_at = :now WHERE id = :id AND deleted_at IS NULL")
			.param("id", fileId)
			.param("now", Timestamp.from(this.clock.instant()))
			.update();
	}

	@Override
	public boolean exists(UUID fileId) {
		return this.store.exists(fileId);
	}

	/** The bytes of a stored file; the caller closes the stream. */
	InputStream open(UUID fileId) {
		return this.store.read(fileId);
	}

	/** Every file {@code accountId} uploaded, deleted ones included, newest first. */
	/** The account's files that are not deleted, newest first: what it still has on the platform. */
	List<StoredFile> keptBy(UUID accountId) {
		return this.jdbc.sql("""
				SELECT id, purpose, account_id, file_name, media_type, kind, size_bytes, stored_at FROM files.stored_file
				WHERE account_id = :account AND deleted_at IS NULL ORDER BY stored_at DESC
				""").param("account", accountId).query((rs, n) -> storedFile(rs)).list();
	}

	List<StoredFile> uploadedBy(UUID accountId) {
		return this.jdbc.sql("""
				SELECT id, purpose, account_id, file_name, media_type, kind, size_bytes, stored_at FROM files.stored_file
				WHERE account_id = :account ORDER BY stored_at DESC
				""").param("account", accountId).query((rs, n) -> storedFile(rs)).list();
	}

	/** @throws ApiException {@code VALIDATION} with {@code FILE_TOO_LARGE} above {@code maxBytes} */
	static void requireSize(byte[] content, long maxBytes) {
		if (content.length > maxBytes) {
			throw new ApiException(ErrorCode.VALIDATION, FilesReason.FILE_TOO_LARGE);
		}
	}

	private void requireQuota(UUID accountId, Instant now) {
		int limit = this.properties.uploadsPerHour();
		List<Instant> recent = this.jdbc.sql("""
				SELECT stored_at FROM files.stored_file WHERE account_id = :account AND stored_at > :since
				ORDER BY stored_at DESC LIMIT :limit
				""")
			.param("account", accountId)
			.param("since", Timestamp.from(now.minus(QUOTA_WINDOW)))
			.param("limit", limit)
			.query((rs, n) -> rs.getTimestamp("stored_at").toInstant())
			.list();
		if (recent.size() >= limit) {
			Instant oldestCounted = recent.get(limit - 1);
			throw new RateLimitedException(Duration.between(now, oldestCounted.plus(QUOTA_WINDOW)),
					FilesReason.UPLOAD_QUOTA);
		}
	}

	private JsonNode reencode(byte[] content, String format) {
		JsonNode input = JsonNodeFactory.instance.objectNode()
			.put("content", Base64.getEncoder().encodeToString(content))
			.put("format", format);
		JsonNode output = this.sandbox.run(ImageReencodeJobHandler.TYPE, input).output();
		String refused = output.path("refused").asString(null);
		if (refused != null) {
			throw new ApiException(ErrorCode.VALIDATION, ImageReencodeJobHandler.IMAGE_TOO_LARGE.equals(refused)
					? FilesReason.IMAGE_TOO_LARGE : FilesReason.FILE_TYPE_NOT_ALLOWED);
		}
		if (!output.path("content").isString() || !output.path("mediaType").isString()
				|| !output.path("extension").isString()) {
			throw new SandboxJobException(ImageReencodeJobHandler.TYPE, "the sandbox answered an incomplete image", null);
		}
		return output;
	}

	private static StoredFile storedFile(ResultSet rs) throws SQLException {
		return new StoredFile(rs.getObject("id", UUID.class), rs.getString("purpose"),
				rs.getObject("account_id", UUID.class), rs.getString("file_name"), rs.getString("media_type"),
				FileKind.valueOf(rs.getString("kind")), rs.getLong("size_bytes"), rs.getTimestamp("stored_at").toInstant());
	}

	private static String sha256(byte[] content) {
		try {
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
		}
		catch (NoSuchAlgorithmException ex) {
			throw new IllegalStateException("Every Java platform has SHA-256", ex);
		}
	}

}
