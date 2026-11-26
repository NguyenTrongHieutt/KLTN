package vn.vibefix.matching.internal;

import java.util.UUID;

import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import vn.vibefix.job.JobPosts;
import vn.vibefix.job.JobPosts.JobPostState;

/**
 * The sweep behind J12 (FR-85, AD-4, B-18): a Self-mentored Job takes no bid. The switch is refused while a bid is
 * open, {@link MatchingListeners} withdraws a bid that committed before it ran, and {@link Applications#submit} reads
 * the Job again after writing the bid; a bid that read the Job before the switch committed and itself commits after the
 * listener ran passes all three. This finds it: every Job that holds a submitted bid is read, and the bids of a
 * Self-mentored one are withdrawn with {@code JOB_SELF_MENTORED}, each bidder told. A Draft is left alone whatever mode
 * it holds: its mode is still a free choice, and nobody switched it (R55). Runs every 6 hours, off when scheduling is
 * off; with no such bid it writes nothing.
 */
@Component
class SelfMentoredBids {

	private final Applications applications;

	private final JobPosts posts;

	SelfMentoredBids(Applications applications, JobPosts posts) {
		this.applications = applications;
		this.posts = posts;
	}

	/** @return how many bids were withdrawn */
	int sweep() {
		int withdrawn = 0;
		for (UUID jobId : this.applications.jobsWithSubmittedBids()) {
			if (this.posts.post(jobId).filter(post -> post.selfMentored() && !"DRAFT".equals(post.state())).isPresent()) {
				withdrawn += this.applications.withdrawForSelfMentored(jobId);
			}
		}
		return withdrawn;
	}

	@Component
	@ConditionalOnBooleanProperty(name = "vibefix.scheduling.enabled", matchIfMissing = true)
	static class Scheduler {

		private final SelfMentoredBids bids;

		Scheduler(SelfMentoredBids bids) {
			this.bids = bids;
		}

		@Scheduled(fixedDelayString = "PT6H", initialDelayString = "PT20M")
		void run() {
			this.bids.sweep();
		}

	}

}
