package vn.vibefix.matching.internal;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import vn.vibefix.identity.PublicProfiles;
import vn.vibefix.identity.PublicProfiles.FactKind;
import vn.vibefix.identity.PublicProfiles.PersonFacts;
import vn.vibefix.job.JobPosts;
import vn.vibefix.job.JobPosts.JobPostState;
import vn.vibefix.job.JobRoles;
import vn.vibefix.job.JobRoles.History;
import vn.vibefix.job.MentorWork;
import vn.vibefix.job.MentorWork.OnTime;

/**
 * {@code matching.fitMetrics} (AD-17, X12, Story 21.3): the only definition of the Fit metrics a Bid card, CUS-12 and
 * CUS-13 show beside the bid, computed for a page of people in one batch of reads of {@code identity} and {@code job}
 * (SG-40). Each is deterministic and stands alone: there is no combined score and nothing orders people by them.
 * The Rating as Mentor and the month joined are profile facts beside them, never metrics.
 * <ul>
 * <li>The Skills shared with the Job Post, by their current names, in the Job Post's order, of the Skills it
 * seeks.</li>
 * <li>The Job Type matches in the person's history in either role ({@code job.historyOf}, one per counterpart), per
 * Job Type of the Job Post.</li>
 * <li>The Jobs closed with Accepted work as Mentor and, separately, as Crew member.</li>
 * <li>The Milestones accepted on time as Mentor, x/y by FR-62's formula.</li>
 * <li>The current active Jobs as Mentor (Selecting through Closing).</li>
 * <li>The Verifications held, in their fixed order.</li>
 * </ul>
 * A metric with nothing to count is empty ("Chưa có dữ liệu"), never a 0 the SPA would show as data.
 */
@Component
class FitMetrics {

	/** A Job Type of the Job Post found in the person's history, with its count in each role. */
	record JobTypeMatch(String jobType, int asMentor, int asCrew) {
	}

	/**
	 * One person's Fit metrics for one Job.
	 * @param sharedSkillIds the Job Post's Skills the person holds, in the Job Post's order
	 * @param skillsSought how many Skills the Job Post seeks
	 * @param jobTypeMatches the Job Post's Job Types found in the history, in the Job Post's order; empty for none
	 * @param onTime the accepted dated Milestones as Mentor; {@code null} for none
	 * @param verifications the Verifications held, in their fixed order
	 */
	record Metrics(List<UUID> sharedSkillIds, int skillsSought, List<JobTypeMatch> jobTypeMatches, int jobsAsMentor,
			int jobsAsCrew, @Nullable OnTime onTime, int activeJobsAsMentor, List<FactKind> verifications) {

		Metrics {
			sharedSkillIds = List.copyOf(sharedSkillIds);
			jobTypeMatches = List.copyOf(jobTypeMatches);
			verifications = List.copyOf(verifications);
		}

	}

	private final JobPosts posts;

	private final JobRoles roles;

	private final MentorWork work;

	private final PublicProfiles profiles;

	FitMetrics(JobPosts posts, JobRoles roles, MentorWork work, PublicProfiles profiles) {
		this.posts = posts;
		this.roles = roles;
		this.work = work;
		this.profiles = profiles;
	}

	/**
	 * The Fit metrics of these people for the Job, keyed by account ID in the order asked; a person {@code identity}
	 * does not know is left out, and so is everyone when the Job does not exist.
	 */
	@Transactional(readOnly = true)
	Map<UUID, Metrics> fitMetrics(UUID jobId, Collection<UUID> accountIds) {
		return fitMetrics(jobId, accountIds, null);
	}

	/**
	 * {@link #fitMetrics(UUID, Collection)} with the people's public facts the caller already read.
	 * @param facts the people's public facts, else {@code null} to read them here
	 */
	@Transactional(readOnly = true)
	Map<UUID, Metrics> fitMetrics(UUID jobId, Collection<UUID> accountIds, @Nullable Map<UUID, PersonFacts> facts) {
		Map<UUID, Metrics> metrics = new LinkedHashMap<>();
		JobPostState post = this.posts.post(jobId).orElse(null);
		if (post == null || accountIds.isEmpty()) {
			return metrics;
		}
		List<UUID> ids = List.copyOf(new LinkedHashSet<>(accountIds));
		Map<UUID, PersonFacts> people = facts != null ? facts : this.profiles.factsOf(ids);
		Map<UUID, History> histories = this.roles.historiesOf(ids);
		Map<UUID, OnTime> onTime = this.work.onTimeMilestones(ids);
		Map<UUID, Integer> active = this.work.activeJobCounts(ids);
		for (UUID id : ids) {
			PersonFacts person = people.get(id);
			if (person == null) {
				continue;
			}
			History history = histories.getOrDefault(id, History.EMPTY);
			List<UUID> shared = post.skillIds().stream().filter(person.skillIds()::contains).toList();
			List<JobTypeMatch> matches = new ArrayList<>();
			for (String type : new LinkedHashSet<>(post.jobTypes())) {
				int asMentor = history.mentor().jobTypes().getOrDefault(type, 0);
				int asCrew = history.crew().jobTypes().getOrDefault(type, 0);
				if (asMentor > 0 || asCrew > 0) {
					matches.add(new JobTypeMatch(type, asMentor, asCrew));
				}
			}
			OnTime dated = onTime.getOrDefault(id, OnTime.NONE);
			metrics.put(id, new Metrics(shared, post.skillIds().size(), matches, history.mentor().jobs(),
					history.crew().jobs(), dated.total() == 0 ? null : dated, active.getOrDefault(id, 0), person.held()));
		}
		return metrics;
	}

}
