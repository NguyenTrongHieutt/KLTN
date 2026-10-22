package vn.vibefix.delivery.internal;

import java.sql.Timestamp;
import java.time.Clock;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import vn.vibefix.delivery.internal.DeliveryAccess.Viewer;
import vn.vibefix.files.FileKind;
import vn.vibefix.files.FileUpload;
import vn.vibefix.files.PreparedFile;
import vn.vibefix.files.StoredFile;
import vn.vibefix.files.StoredFiles;
import vn.vibefix.job.JobUploads;
import vn.vibefix.shared.ApiException;
import vn.vibefix.shared.ErrorCode;

/**
 * A handover's screenshots (FR-32, AD-21, AD-34): images the Mentor uploads on "Tạo bàn giao", re-encoded
 * in the sandbox by {@code files} and counted toward the Job's {@value JobUploads#QUOTA_BYTES} bytes with
 * its own screenshots. An upload is the uploader's alone until "Gửi bàn giao" attaches it, at most
 * {@value #MAX} per handover; the uploader may remove it before then.
 */
@Component
class HandoverScreenshots {

	static final String PURPOSE = "delivery.handover_screenshot";

	/** Screenshots per handover. */
	static final int MAX = 5;

	/** Uploads waiting for a handover, per Mentor and Job. */
	static final int MAX_PENDING = 10;

	record Screenshot(UUID fileId, UUID jobId, @Nullable UUID accountId, String fileName, long size,
			@Nullable UUID handoverId) {
	}

	private final JdbcClient jdbc;

	private final StoredFiles files;

	private final JobUploads uploads;

	private final Clock clock;

	HandoverScreenshots(JdbcClient jdbc, StoredFiles files, JobUploads uploads, Clock clock) {
		this.jdbc = jdbc;
		this.files = files;
		this.uploads = uploads;
		this.clock = clock;
	}

	/** Checks an image outside any transaction: it goes through the sandbox (AD-21). */
	PreparedFile prepare(UUID accountId, String fileName, byte[] content) {
		return this.files.prepare(new FileUpload(PURPOSE, accountId, fileName, content, Set.of(FileKind.IMAGE)));
	}

	/**
	 * Keeps a checked image for the Mentor's next handover.
	 * @throws ApiException {@code VALIDATION JOB_FILE_QUOTA} past the Job's quota; {@code VALIDATION
	 * TOO_MANY_SCREENSHOTS} past {@value #MAX_PENDING} uploads waiting
	 */
	@Transactional
	Screenshot add(Viewer mentor, PreparedFile file) {
		UUID jobId = mentor.job().jobId();
		long used = this.uploads.screenshotBytes(jobId) + this.jdbc.sql(
				"SELECT coalesce(sum(size_bytes), 0) FROM delivery.handover_screenshot WHERE job_id = :job")
			.param("job", jobId)
			.query(Long.class)
			.single();
		if (used + file.size() > JobUploads.QUOTA_BYTES) {
			throw new ApiException(ErrorCode.VALIDATION, DeliveryReason.JOB_FILE_QUOTA);
		}
		if (pending(jobId, mentor.accountId()).size() >= MAX_PENDING) {
			throw new ApiException(ErrorCode.VALIDATION, DeliveryReason.TOO_MANY_SCREENSHOTS);
		}
		StoredFile stored = this.files.store(file);
		this.jdbc.sql("""
				INSERT INTO delivery.handover_screenshot (file_id, job_id, product_id, account_id, file_name, size_bytes, uploaded_at)
				VALUES (:file, :job, :product, :account, :name, :size, :now)
				""")
			.param("file", stored.id())
			.param("job", jobId)
			.param("product", mentor.job().productId())
			.param("account", mentor.accountId())
			.param("name", stored.fileName())
			.param("size", stored.size())
			.param("now", Timestamp.from(this.clock.instant()))
			.update();
		return new Screenshot(stored.id(), jobId, mentor.accountId(), stored.fileName(), stored.size(), null);
	}

	/**
	 * Removes an upload that is not attached yet, and its file.
	 * @throws ApiException {@code NOT_FOUND} for anyone else's, or an attached one
	 */
	@Transactional
	void remove(Viewer mentor, UUID fileId) {
		int removed = this.jdbc.sql("""
				DELETE FROM delivery.handover_screenshot
				WHERE file_id = :file AND job_id = :job AND account_id = :account AND handover_id IS NULL
				""").param("file", fileId).param("job", mentor.job().jobId()).param("account", mentor.accountId()).update();
		if (removed == 0) {
			throw new ApiException(ErrorCode.NOT_FOUND);
		}
		this.files.delete(fileId);
	}

	/** The uploads waiting for the Mentor's next handover of the Job, oldest first. */
	@Transactional(readOnly = true)
	List<Screenshot> pending(UUID jobId, UUID accountId) {
		return this.jdbc.sql("SELECT " + COLUMNS + """
				 FROM delivery.handover_screenshot
				WHERE job_id = :job AND account_id = :account AND handover_id IS NULL ORDER BY uploaded_at, file_id
				""").param("job", jobId).param("account", accountId).query(HandoverScreenshots::screenshot).list();
	}

	/**
	 * The uploads a handover would attach, checked before anything is written.
	 * @throws ApiException {@code VALIDATION TOO_MANY_SCREENSHOTS} past {@value #MAX}; {@code VALIDATION
	 * SCREENSHOT_UNKNOWN} for one that is not the Mentor's waiting upload on the Job
	 */
	void requireAttachable(UUID jobId, UUID accountId, List<UUID> fileIds) {
		if (fileIds.size() > MAX) {
			throw new ApiException(ErrorCode.VALIDATION, DeliveryReason.TOO_MANY_SCREENSHOTS);
		}
		List<UUID> waiting = pending(jobId, accountId).stream().map(Screenshot::fileId).toList();
		if (Set.copyOf(fileIds).size() != fileIds.size() || !waiting.containsAll(fileIds)) {
			throw new ApiException(ErrorCode.VALIDATION, DeliveryReason.SCREENSHOT_UNKNOWN);
		}
	}

	/** Attaches the uploads to the handover, in the caller's transaction, in their order. */
	void attach(UUID handoverId, UUID jobId, UUID accountId, List<UUID> fileIds) {
		for (int position = 0; position < fileIds.size(); position++) {
			int attached = this.jdbc.sql("""
					UPDATE delivery.handover_screenshot SET handover_id = :handover, position = :position
					WHERE file_id = :file AND job_id = :job AND account_id = :account AND handover_id IS NULL
					""").param("handover", handoverId).param("position", position).param("file", fileIds.get(position))
				.param("job", jobId).param("account", accountId).update();
			if (attached == 0) {
				throw new ApiException(ErrorCode.VALIDATION, DeliveryReason.SCREENSHOT_UNKNOWN);
			}
		}
	}

	@Transactional(readOnly = true)
	List<Screenshot> of(UUID handoverId) {
		return this.jdbc.sql("SELECT " + COLUMNS + " FROM delivery.handover_screenshot WHERE handover_id = :id ORDER BY position")
			.param("id", handoverId)
			.query(HandoverScreenshots::screenshot)
			.list();
	}

	@Transactional(readOnly = true)
	Optional<Screenshot> find(UUID fileId) {
		return this.jdbc.sql("SELECT " + COLUMNS + " FROM delivery.handover_screenshot WHERE file_id = :id")
			.param("id", fileId)
			.query(HandoverScreenshots::screenshot)
			.optional();
	}

	private static final String COLUMNS = "file_id, job_id, account_id, file_name, size_bytes, handover_id";

	private static Screenshot screenshot(java.sql.ResultSet rs, int n) throws java.sql.SQLException {
		return new Screenshot(rs.getObject("file_id", UUID.class), rs.getObject("job_id", UUID.class),
				rs.getObject("account_id", UUID.class), rs.getString("file_name"), rs.getLong("size_bytes"),
				rs.getObject("handover_id", UUID.class));
	}

}
