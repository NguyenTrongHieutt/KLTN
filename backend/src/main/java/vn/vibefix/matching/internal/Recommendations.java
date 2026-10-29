package vn.vibefix.matching.internal;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import vn.vibefix.identity.MentorDirectory;
import vn.vibefix.identity.MentorDirectory.MentorSkills;
import vn.vibefix.job.JobPosts;
import vn.vibefix.job.JobPosts.JobNeed;
import vn.vibefix.job.JobPosts.JobPostState;
import vn.vibefix.job.JobPosts.ListedPost;
import vn.vibefix.job.JobRoles;
import vn.vibefix.job.JobRoles.History;
import vn.vibefix.job.MentorWork;

/**
 * Deterministic Recommendations (FR-14, AD-17), with no AI and no hidden weight: Mentors for a Job
 * Post and listed Jobs for a user, each with the signals that placed it. The inputs are batch reads
 * of {@code identity} and {@code job} for the page being built, never a cross-schema join (SG-40).
 * Ratings never change the order, and nothing here selects, invites, or assigns anyone.
 */
@Component
class Recommendations {

	/** Mentors shown for a Job Post (CUS-8). */
	static final int FOR_JOB_POST = 6;

	/** Listed Jobs shown for a user (MEN-2). */
	static final int FOR_MENTOR = 4;

	/** Mentors sharing Skills read at most, before eligibility. */
	static final int POOL = 200;

	/** The best-placed new Mentor stands no lower than 5th (FR-14). */
	static final int NEW_MENTOR_PLACE = 4;

	/**
	 * A Job Type of the Job Post found in the history in either role, with how many counterparts it counts (Customers
	 * as Mentor, Mentors as Crew member, summed).
	 */
	record JobTypeMatch(String jobType, int count) {
	}

	/**
	 * One Mentor for a Job Post.
	 * @param sharedSkillIds the Job Post's Skills the Mentor holds, in the Job Post's order
	 * @param newcomer no Job closed with Accepted work
	 */
	record Candidate(UUID mentorId, List<UUID> skillIds, List<UUID> sharedSkillIds, List<JobTypeMatch> jobTypeMatches,
			int activeJobs, boolean newcomer) {

		int history() {
			return this.jobTypeMatches.stream().mapToInt(JobTypeMatch::count).sum();
		}

	}

	/**
	 * One listed Job for a user.
	 * @param sharedSkillIds the user's Skills among the Job Post's and, while recruiting, the Skills sought, in that
	 * order
	 */
	record RecommendedPost(ListedPost post, List<UUID> sharedSkillIds, List<JobTypeMatch> jobTypeMatches) {

		int history() {
			return this.jobTypeMatches.stream().mapToInt(JobTypeMatch::count).sum();
		}

	}

	private final MentorDirectory mentors;

	private final MentorWork work;

	private final JobPosts posts;

	private final JobRoles roles;

	private final JdbcClient jdbc;

	Recommendations(MentorDirectory mentors, MentorWork work, JobPosts posts, JobRoles roles, JdbcClient jdbc) {
		this.mentors = mentors;
		this.work = work;
		this.posts = posts;
		this.roles = roles;
		this.jdbc = jdbc;
	}

	/**
	 * Mentors for the Job Post (AD-17): available users, never the Job's Customer, under the Mentor Job limit
	 * (FR-13), who share a Skill or a Job Type of their history in either role ({@code job.historyOf}) with it, in
	 * {@link #order}; the Skill filter then narrows them without reordering. Nobody needs a free Student any more
	 * (Story 21.1). A new Mentor has no Job closed with Accepted work as Mentor.
	 */
	@Transactional(readOnly = true)
	List<Candidate> forJobPost(JobPostState post, @Nullable Set<UUID> skillFilter) {
		Map<UUID, List<UUID>> pool = new LinkedHashMap<>();
		for (MentorSkills mentor : this.mentors.holdingAny(post.skillIds(), POOL)) {
			pool.put(mentor.accountId(), mentor.skillIds());
		}
		List<UUID> experienced = this.work.experiencedIn(post.jobTypes())
			.stream()
			.filter(id -> !pool.containsKey(id))
			.toList();
		pool.putAll(this.mentors.skillIdsOf(experienced));
		// The Job's Customer is never its Mentor (AD-43): never recommended for their own Job.
		pool.remove(post.customerId());
		if (pool.isEmpty()) {
			return List.of();
		}
		Map<UUID, Integer> active = this.work.activeJobCounts(pool.keySet());
		int limit = this.work.activeJobLimit();
		// The history matching.fitMetrics reads (Story 21.3): job.historiesOf in either role, one per counterpart, so a
		// Recommendation's Job Type count is the one the same person's Bid card shows.
		Map<UUID, History> histories = this.roles.historiesOf(pool.keySet());
		List<Candidate> candidates = new ArrayList<>();
		pool.forEach((mentor, skills) -> {
			int jobs = active.getOrDefault(mentor, 0);
			if (jobs >= limit) {
				return;
			}
			List<UUID> shared = post.skillIds().stream().filter(skills::contains).toList();
			History history = histories.getOrDefault(mentor, History.EMPTY);
			List<JobTypeMatch> matches = matches(post.jobTypes(), eitherRole(history));
			if (!shared.isEmpty() || !matches.isEmpty()) {
				candidates.add(new Candidate(mentor, skills, shared, matches, jobs, history.mentor().jobs() == 0));
			}
		});
		return order(candidates).stream()
			.filter(candidate -> SkillFilter.keeps(skillFilter, candidate.skillIds()))
			.limit(FOR_JOB_POST)
			.toList();
	}

	/**
	 * Listed Jobs for a user (AD-17, Story 21.1): Jobs "Tìm việc" lists (AD-36) where they hold no role (AD-43), with no
	 * bid of any status while the Job needs a Mentor and no pending Crew Application of theirs (Story 21.4: a bidder not
	 * selected may still apply to the Crew, R3), that share a Skill (the Job Post's, or while recruiting the Skills
	 * sought) or a Job Type of their history in either role ({@code job.historyOf}): by shared Skills, then Job Type
	 * matches, then newest first; the Skill filter, on the same Skills, narrows them without reordering.
	 */
	@Transactional(readOnly = true)
	List<RecommendedPost> forMentor(UUID userId, @Nullable Set<UUID> skillFilter) {
		// The caller's own Skills, as their profile holds them, whether or not they may be invited (Story 19.7).
		List<UUID> skills = this.mentors.profileSkillIds(userId);
		Map<String, Integer> done = eitherRole(this.roles.historyOf(userId));
		Set<UUID> bid = new HashSet<>(this.jdbc
			.sql("SELECT job_id FROM matching.application WHERE mentor_account_id = :user")
			.param("user", userId)
			.query(UUID.class)
			.list());
		Set<UUID> applied = new HashSet<>(this.jdbc
			.sql("SELECT job_id FROM matching.crew_application WHERE applicant_account_id = :user AND status = 'PENDING'")
			.param("user", userId)
			.query(UUID.class)
			.list());
		List<RecommendedPost> recommended = new ArrayList<>();
		for (ListedPost post : this.posts.listedPosts(userId)) {
			if ((post.need() == JobNeed.MENTOR && bid.contains(post.jobId())) || applied.contains(post.jobId())) {
				continue;
			}
			List<UUID> sought = sought(post);
			List<UUID> shared = sought.stream().filter(skills::contains).toList();
			List<JobTypeMatch> matches = matches(post.jobTypes(), done);
			if (!shared.isEmpty() || !matches.isEmpty()) {
				recommended.add(new RecommendedPost(post, shared, matches));
			}
		}
		return recommended.stream()
			.sorted(Comparator.comparingInt((RecommendedPost found) -> found.sharedSkillIds().size())
				.reversed()
				.thenComparing(Comparator.comparingInt(RecommendedPost::history).reversed())
				.thenComparing((RecommendedPost found) -> found.post().listedAt(), Comparator.reverseOrder())
				.thenComparing(found -> found.post().jobId().toString()))
			.filter(found -> SkillFilter.keeps(skillFilter, sought(found.post())))
			.limit(FOR_MENTOR)
			.toList();
	}

	/** The Job Post's Skills, then, while recruiting, the Skills sought that it does not name. */
	private static List<UUID> sought(ListedPost post) {
		Set<UUID> sought = new LinkedHashSet<>(post.skillIds());
		sought.addAll(post.recruitSkillIds());
		return List.copyOf(sought);
	}

	/** The Jobs closed with Accepted work in either role, per Job Type: the Mentor's and the Crew member's summed. */
	private static Map<String, Integer> eitherRole(History history) {
		Map<String, Integer> done = new HashMap<>(history.mentor().jobTypes());
		history.crew().jobTypes().forEach((type, count) -> done.merge(type, count, Integer::sum));
		return done;
	}

	/**
	 * AD-17's order: shared Skills descending, Job Type matches descending, active Jobs ascending, then
	 * account ID as PostgreSQL orders UUIDs (its hex text); the best-placed new Mentor then moves up to
	 * {@value #NEW_MENTOR_PLACE} (5th) when lower.
	 */
	static List<Candidate> order(List<Candidate> candidates) {
		List<Candidate> ordered = new ArrayList<>(candidates);
		ordered.sort(Comparator.comparingInt((Candidate candidate) -> candidate.sharedSkillIds().size())
			.reversed()
			.thenComparing(Comparator.comparingInt(Candidate::history).reversed())
			.thenComparingInt(Candidate::activeJobs)
			.thenComparing(candidate -> candidate.mentorId().toString()));
		for (int i = NEW_MENTOR_PLACE + 1; i < ordered.size(); i++) {
			if (ordered.get(i).newcomer()) {
				boolean placed = ordered.subList(0, NEW_MENTOR_PLACE + 1).stream().anyMatch(Candidate::newcomer);
				if (!placed) {
					ordered.add(NEW_MENTOR_PLACE, ordered.remove(i));
				}
				break;
			}
		}
		return ordered;
	}

	private static List<JobTypeMatch> matches(List<String> jobTypes, Map<String, Integer> done) {
		List<JobTypeMatch> matches = new ArrayList<>();
		Map<String, Integer> counted = new HashMap<>(done);
		for (String type : jobTypes) {
			Integer count = counted.remove(type);
			if (count != null && count > 0) {
				matches.add(new JobTypeMatch(type, count));
			}
		}
		return matches;
	}

}
