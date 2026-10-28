package vn.vibefix.job;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.jspecify.annotations.Nullable;

/** Job Posts as other modules read them (FR-12, AD-3), and the Jobs "Tìm việc" lists (AD-36). */
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

	/** What a listed Job needs (UX-DR97): a Mentor while it takes bids, Crew members while its Mentor recruits. */
	enum JobNeed {

		/** "Cần người hướng dẫn". */
		MENTOR,

		/** "Cần thêm {n} thành viên nhóm". */
		CREW

	}

	/**
	 * A Job listed on "Tìm việc" (AD-36), as Recommendations rank it (AD-17): not hidden, and Open, or in Agreement,
	 * Starting or Active with Crew recruitment on and a free slot.
	 * @param budget the budget range's name, if any
	 * @param openedAt when the Job Post opened
	 * @param phase {@link JobPhase#phaseOf}, counting only the {@code ACTIVE} members whose accounts are unlocked
	 * @param openSlots the places still open while recruitment is on, else 0
	 * @param recruitSkillIds the Skills sought while recruitment is on, in the Mentor's order
	 * @param listedAt when recruitment turned on, else when the Job Post opened: "newest" orders by it
	 */
	record ListedPost(UUID jobId, UUID customerId, UUID productId, String title, List<String> jobTypes,
			List<UUID> skillIds, @Nullable String budget, @Nullable LocalDate deadline, Instant openedAt,
			@Nullable JobPhase phase, JobNeed need, int openSlots, List<UUID> recruitSkillIds, Instant listedAt) {

		public ListedPost {
			jobTypes = List.copyOf(jobTypes);
			skillIds = List.copyOf(skillIds);
			recruitSkillIds = List.copyOf(recruitSkillIds);
		}

	}

	/** Any Job by ID, whatever its state; empty for no such Job. */
	Optional<JobPostState> post(UUID jobId);

	/** Every Job of a Product, whatever its state, newest first: the earlier Jobs FR-70 offers. */
	List<JobPostState> productJobs(UUID productId);

	/** The Customer of any Job, for checking that a caller is it. */
	Optional<UUID> customerOf(UUID jobId);

	/**
	 * The Customer of a listed Job (AD-36), for the Rating summary MEN-3 shows; empty for a Draft, a hidden Job, or
	 * a Job no longer listed.
	 */
	Optional<UUID> listedCustomer(UUID jobId);

	/**
	 * The Customers of those of {@code jobIds} that are listed, keyed by Job, in one read: a page of "Tìm việc" rows
	 * shows each one's Rating summary without naming them (FR-12, SG-43).
	 */
	Map<UUID, UUID> listedCustomers(Collection<UUID> jobIds);

	/**
	 * Every listed Job where {@code reader} holds no role (AD-43): not their Customer's, not their Mentor's, and with
	 * no counted Crew row of theirs; newest first, in one read (AD-17, SG-40).
	 */
	List<ListedPost> listedPosts(UUID reader);

}
