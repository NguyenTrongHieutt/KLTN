package vn.vibefix.job;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.jspecify.annotations.Nullable;

/**
 * ADM-5 (FR-68, FR-63, UX-DR69): Jobs as Administrators oversee them, by state and by flag, each with its
 * live Milestones. Nothing here reads a Job Chat or a Crew Chat.
 */
public interface JobOversight {

	/** A page holds this many Jobs. */
	int PAGE_SIZE = 20;

	/** {@code OVERDUE}: a Funded or Revision Requested Milestone is overdue; {@code FROZEN}: a Dispute froze it. */
	enum Flag {

		OVERDUE, FROZEN

	}

	record MilestoneLine(UUID id, int number, String title, String state, @Nullable LocalDate dueOn,
			@Nullable Instant overdueSince) {
	}

	record JobLine(UUID id, String title, String state, UUID customerId, @Nullable UUID mentorId, boolean frozen,
			boolean overdue, Instant updatedAt, List<MilestoneLine> milestones) {
	}

	record Page(List<JobLine> items, long total, int page, int size) {
	}

	/** Jobs past Draft, newest change first; {@code state} narrows to one state, Draft included. */
	Page search(@Nullable String state, @Nullable Flag flag, int page);

	Optional<JobLine> job(UUID jobId);

}
