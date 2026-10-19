package vn.vibefix.files;

import java.time.Instant;
import java.util.UUID;

/**
 * A stored file's metadata (AD-21).
 * @param fileName the normalized name, served in {@code Content-Disposition}
 * @param size the stored size in bytes
 */
public record StoredFile(UUID id, String purpose, UUID accountId, String fileName, String mediaType, FileKind kind,
		long size, Instant storedAt) {
}
