package vn.vibefix.scanning;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.jspecify.annotations.Nullable;

/**
 * One scan run as CUS-2 and CUS-4 show it.
 * @param found distinct credentials the run found, once it succeeded
 * @param unscannedCount files over the size limit ("chưa kiểm tra được"); {@code unscanned} lists the first 50
 */
public record ScanSummary(UUID id, ScanPurpose purpose, ScanStatus status, Instant requestedAt,
		@Nullable Instant finishedAt, @Nullable Integer found, int unscannedCount, List<UnscannedFile> unscanned) {

	/** A file the scan did not read because of its size. */
	public record UnscannedFile(String path, long sizeBytes) {
	}

	public ScanSummary {
		unscanned = List.copyOf(unscanned);
	}

}
