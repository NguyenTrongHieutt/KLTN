package vn.vibefix.repo;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.jspecify.annotations.Nullable;

/**
 * A Job's Drift Warnings as a Dispute's Evidence Package reads them (FR-20, FR-53, AD-12): each change of
 * the Source Repository, with the commits that triggered it.
 */
public interface DriftEvidence {

	/**
	 * One Drift Warning.
	 * @param kind {@code COMMITS}, {@code GONE}, {@code ARCHIVED}, {@code RENAMED}, {@code TRANSFERRED},
	 * {@code APP_UNINSTALLED}, or {@code WORKFLOW_CHANGED} (Story 23.5b; an Evidence Package snapshotted earlier may
	 * still name a kind retired since)
	 * @param commits the SHAs past the copied commit that triggered a {@code COMMITS} warning
	 * @param reconfirmedAt when the Customer re-confirmed a renamed or transferred repository
	 */
	record Warning(UUID id, String kind, @Nullable String headSha, @Nullable String fullName, List<String> commits,
			Instant detectedAt, @Nullable Instant reconfirmedAt) {

		public Warning {
			commits = List.copyOf(commits);
		}

	}

	/** The Job's Drift Warnings, oldest first. */
	List<Warning> driftWarnings(UUID jobId);

}
