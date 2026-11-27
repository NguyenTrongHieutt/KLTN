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

	/**
	 * @param alone the Customer of a Self-mentored Job confirmed this version in one step (AD-47): nobody approved it
	 * first, and {@code approvedAt} is the time of that one step. Only version 1 is ever confirmed so: it is told by
	 * the kind of draft the version names, never by the Customer and the Mentor being one account, and a later
	 * version of such a Job, which a Change Request makes, is not one
	 */
	record ConfirmedVersion(int versionNo, Instant approvedAt, Instant confirmedAt, String scope, @Nullable Long priceVnd,
			List<MilestoneLine> milestones, boolean alone) {

		public ConfirmedVersion {
			milestones = List.copyOf(milestones);
		}

	}

	/** Every confirmed version, the first first. */
	List<ConfirmedVersion> confirmedVersions(UUID jobId);

}
