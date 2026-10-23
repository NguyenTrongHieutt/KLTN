package vn.vibefix.scanning;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.jspecify.annotations.Nullable;

/**
 * One scan run as CUS-2 and CUS-4 show it.
 * @param found distinct credentials the run found, once it succeeded
 * @param unscannedCount files not read ("chưa kiểm tra được"); {@code unscanned} lists the first 50
 * @param defaultBranch the default branch the run read, once it succeeded
 * @param defaultBranchSha that branch's head as the run read it: what a J7 run approves for the copy
 */
public record ScanSummary(UUID id, ScanPurpose purpose, ScanStatus status, Instant requestedAt,
		@Nullable Instant finishedAt, @Nullable Integer found, int unscannedCount, List<UnscannedFile> unscanned,
		@Nullable String defaultBranch, @Nullable String defaultBranchSha) {

	/**
	 * A file the scan did not read.
	 * @param reason {@code TOO_LARGE}; {@code NOT_EXTRACTED} for a spreadsheet or database file whose extraction
	 * is off (FR-65 Tier 3); or {@code UNREADABLE} for one the extraction could not read (Story 16.3)
	 */
	public record UnscannedFile(String path, long sizeBytes, String reason) {
	}

	public ScanSummary {
		unscanned = List.copyOf(unscanned);
	}

}
