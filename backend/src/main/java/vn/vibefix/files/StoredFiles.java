package vn.vibefix.files;

import java.util.Optional;
import java.util.UUID;

/**
 * Checks, stores, and deletes files (AD-21, AD-34). A module that accepts uploads calls
 * {@link #prepare} before its transaction, since an image goes through the sandbox, then
 * {@link #store} inside it, where it records the returned ID with its own data.
 */
public interface StoredFiles {

	/**
	 * Checks the upload and re-encodes an image.
	 * @throws vn.vibefix.shared.RateLimitedException with {@code UPLOAD_QUOTA} past 30 uploads in an hour
	 * @throws vn.vibefix.shared.ApiException {@code VALIDATION} with {@code FILE_TOO_LARGE} above 10 MB,
	 * {@code FILE_TYPE_NOT_ALLOWED} for a kind the upload does not accept or an unreadable image, or
	 * {@code IMAGE_TOO_LARGE} above 40 megapixels
	 * @throws vn.vibefix.shared.SandboxJobException if the image could not be re-encoded
	 */
	PreparedFile prepare(FileUpload upload);

	/**
	 * Stores a prepared file in the caller's transaction; a rollback deletes the bytes again. The
	 * quota is checked once more under the account's lock.
	 * @throws vn.vibefix.shared.RateLimitedException with {@code UPLOAD_QUOTA}
	 * @throws org.springframework.transaction.IllegalTransactionStateException without a transaction
	 */
	StoredFile store(PreparedFile file);

	/** The file's metadata, unless it is deleted. */
	Optional<StoredFile> find(UUID fileId);

	/** Deletes the bytes, then marks the file deleted; deleting a deleted file changes nothing. */
	void delete(UUID fileId);

	/** Whether the file's bytes are still on the volume. */
	boolean exists(UUID fileId);

}
