package vn.vibefix.job;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.jspecify.annotations.Nullable;

/** Open Job Posts as other modules read them (FR-12, AD-3). */
public interface JobPosts {

	/**
	 * A Job as {@code matching} decides on Applications to it.
	 * @param state the Job state's name, for example {@code SELECTING}
	 * @param open in the Open state
	 * @param hidden hidden by an Administrator (FR-60)
	 * @param lapsesAt when the Job Post lapses, while Open
	 * @param codeJob a Code Job: its Job Types are not Assessment alone (FR-8)
	 * @param mentorId the Mentor J3 recorded, while there is one
	 * @param jobTypes the Job Types' names, for example {@code BUG_FIX}
	 * @param skillIds the Skills the Job Post asks for, in its order
	 */
	record JobPostState(UUID jobId, UUID customerId, UUID productId, String title, String state, boolean open,
			boolean hidden, @Nullable Instant lapsesAt, boolean codeJob, @Nullable UUID mentorId, List<String> jobTypes,
			List<UUID> skillIds) {

		public JobPostState {
			jobTypes = List.copyOf(jobTypes);
			skillIds = List.copyOf(skillIds);
		}

	}

	/**
	 * An Open Job Post that is not hidden, as Recommendations rank it (AD-17).
	 * @param budget the budget range's name, if any
	 */
	record OpenPost(UUID jobId, UUID customerId, UUID productId, String title, List<String> jobTypes,
			List<UUID> skillIds, @Nullable String budget, @Nullable LocalDate deadline, Instant openedAt) {

		public OpenPost {
			jobTypes = List.copyOf(jobTypes);
			skillIds = List.copyOf(skillIds);
		}

	}

	/** Any Job by ID, whatever its state; empty for no such Job. */
	Optional<JobPostState> post(UUID jobId);

	/** The Customer of any Job, for checking that a caller is it. */
	Optional<UUID> customerOf(UUID jobId);

	/**
	 * The Customer of an Open Job Post that is not hidden (FR-60), for the Rating summary MEN-3 shows;
	 * empty for a Draft, a hidden Job Post, or a Job past Open.
	 */
	Optional<UUID> openCustomer(UUID jobId);

	/** Every Open Job Post that is not hidden, newest first, in one read (AD-17, SG-40). */
	List<OpenPost> openPosts();

}
