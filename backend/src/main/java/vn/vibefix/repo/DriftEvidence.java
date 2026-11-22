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
	 * @param headSha none for a {@code COMMITS} warning since Story 24.6, which names no head; the head a retired daily
	 * poll found, for one of its own
	 * @param commits a {@code COMMITS} warning's SHAs: since Story 24.6 those pushed to the branch the copy is made from
	 * after the Customer's confirmation, newest last, which every later push adds to; a warning the retired daily poll
	 * made keeps the SHAs past the copied commit it found
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
