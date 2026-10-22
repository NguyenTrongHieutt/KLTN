package vn.vibefix.job;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import org.jspecify.annotations.Nullable;

/**
 * A Job's confirmed Agreement versions as a Dispute's Evidence Package reads them (FR-53, AD-31): what each
 * version said and when the Customer approved and the Mentor confirmed it. Never who is paid what.
 */
public interface AgreementHistory {

	record CriterionLine(UUID criterionId, int number, String statement, String checkSteps) {
	}

	record MilestoneLine(UUID milestoneId, int number, String title, @Nullable Long priceVnd, @Nullable LocalDate dueOn,
			List<CriterionLine> criteria) {

		public MilestoneLine {
			criteria = List.copyOf(criteria);
		}

	}

	record ConfirmedVersion(int versionNo, Instant approvedAt, Instant confirmedAt, String scope, @Nullable Long priceVnd,
			List<MilestoneLine> milestones) {

		public ConfirmedVersion {
			milestones = List.copyOf(milestones);
		}

	}

	/** Every confirmed version, the first first. */
	List<ConfirmedVersion> confirmedVersions(UUID jobId);

}
