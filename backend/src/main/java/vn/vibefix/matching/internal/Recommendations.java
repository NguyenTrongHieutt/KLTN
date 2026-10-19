package vn.vibefix.matching.internal;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
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
import vn.vibefix.job.JobPosts.JobPostState;
import vn.vibefix.job.JobPosts.OpenPost;
import vn.vibefix.job.MentorWork;

/**
 * Deterministic Recommendations (FR-14, AD-17), with no AI and no hidden weight: Mentors for a Job
 * Post and Job Posts for a Mentor, each with the signals that placed it. The inputs are batch reads
 * of {@code identity}, {@code team} (through {@code job}), and {@code job} for the page being built,
 * never a cross-schema join (SG-40). Ratings never change the order, and nothing here selects,
 * invites, or assigns anyone.
 */
@Component
class Recommendations {

	/** Mentors shown for a Job Post (CUS-8). */
	static final int FOR_JOB_POST = 6;

	/** Job Posts shown for a Mentor (MEN-2). */
	static final int FOR_MENTOR = 4;

	/** Mentors sharing Skills read at most, before eligibility. */
	static final int POOL = 200;

	/** The best-placed new Mentor stands no lower than 5th (FR-14). */
	static final int NEW_MENTOR_PLACE = 4;

	/** A Job Type of the Job Post found in the work history, with how many Customers it counts. */
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

	/** One Job Post for a Mentor. */
	record RecommendedPost(OpenPost post, List<UUID> sharedSkillIds, List<JobTypeMatch> jobTypeMatches) {

		int history() {
			return this.jobTypeMatches.stream().mapToInt(JobTypeMatch::count).sum();
		}

	}

	private final MentorDirectory mentors;

	private final MentorWork work;

	private final JobPosts posts;

	private final JdbcClient jdbc;

	Recommendations(MentorDirectory mentors, MentorWork work, JobPosts posts, JdbcClient jdbc) {
		this.mentors = mentors;
		this.work = work;
		this.posts = posts;
		this.jdbc = jdbc;
	}

	/**
	 * Mentors for the Job Post (AD-17): approved, unlocked Mentors who could apply under FR-13 and share
	 * a Skill or a Job Type of their work history with it, in {@link #order}; the Skill filter then
	 * narrows them without reordering.
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
		if (pool.isEmpty()) {
			return List.of();
		}
		Set<UUID> free = this.work.withFreeStudents(pool.keySet());
		Map<UUID, Integer> active = this.work.activeJobCounts(pool.keySet());
		int limit = this.work.activeJobLimit();
		Map<UUID, Map<String, Integer>> history = this.work.workHistory(pool.keySet());
		List<Candidate> candidates = new ArrayList<>();
		pool.forEach((mentor, skills) -> {
			int jobs = active.getOrDefault(mentor, 0);
			if (!free.contains(mentor) || jobs >= limit) {
				return;
			}
			List<UUID> shared = post.skillIds().stream().filter(skills::contains).toList();
			Map<String, Integer> done = history.getOrDefault(mentor, Map.of());
			List<JobTypeMatch> matches = matches(post.jobTypes(), done);
			if (!shared.isEmpty() || !matches.isEmpty()) {
				candidates.add(new Candidate(mentor, skills, shared, matches, jobs, done.isEmpty()));
			}
		});
		return order(candidates).stream()
			.filter(candidate -> SkillFilter.keeps(skillFilter, candidate.skillIds()))
			.limit(FOR_JOB_POST)
			.toList();
	}

	/**
	 * Job Posts for the Mentor (AD-17): Open, non-hidden ones not yet applied to that share a Skill or a
	 * Job Type of their work history, by shared Skills, then Job Type matches, then newest first; the
	 * Skill filter narrows them without reordering.
	 */
	@Transactional(readOnly = true)
	List<RecommendedPost> forMentor(UUID mentorId, @Nullable Set<UUID> skillFilter) {
		List<UUID> skills = this.mentors.skillIdsOf(List.of(mentorId)).getOrDefault(mentorId, List.of());
		Map<String, Integer> done = this.work.workHistory(List.of(mentorId)).getOrDefault(mentorId, Map.of());
		Set<UUID> applied = new HashSet<>(this.jdbc
			.sql("SELECT job_id FROM matching.application WHERE mentor_account_id = :mentor")
			.param("mentor", mentorId)
			.query(UUID.class)
			.list());
		List<RecommendedPost> recommended = new ArrayList<>();
		for (OpenPost post : this.posts.openPosts()) {
			if (applied.contains(post.jobId())) {
				continue;
			}
			List<UUID> shared = post.skillIds().stream().filter(skills::contains).toList();
			List<JobTypeMatch> matches = matches(post.jobTypes(), done);
			if (!shared.isEmpty() || !matches.isEmpty()) {
				recommended.add(new RecommendedPost(post, shared, matches));
			}
		}
		return recommended.stream()
			.sorted(Comparator.comparingInt((RecommendedPost found) -> found.sharedSkillIds().size())
				.reversed()
				.thenComparing(Comparator.comparingInt(RecommendedPost::history).reversed())
				.thenComparing((RecommendedPost found) -> found.post().openedAt(), Comparator.reverseOrder())
				.thenComparing(found -> found.post().jobId().toString()))
			.filter(found -> SkillFilter.keeps(skillFilter, found.post().skillIds()))
			.limit(FOR_MENTOR)
			.toList();
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
