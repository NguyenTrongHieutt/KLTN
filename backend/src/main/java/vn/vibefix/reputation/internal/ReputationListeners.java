package vn.vibefix.reputation.internal;

import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

import vn.vibefix.job.JobPosts;
import vn.vibefix.job.JobTransitioned;
import vn.vibefix.shared.ProcessedEvents;

/**
 * J9 opens the Rating Window (FR-49, FR-78) for the Job's copied {@code RATING_WINDOW_DAYS}, which the
 * transition carries. Replay-safe.
 */
@Component
class ReputationListeners {

	private final Ratings ratings;

	private final JobPosts posts;

	private final ProcessedEvents processedEvents;

	ReputationListeners(Ratings ratings, JobPosts posts, ProcessedEvents processedEvents) {
		this.ratings = ratings;
		this.posts = posts;
		this.processedEvents = processedEvents;
	}

	@ApplicationModuleListener
	void onJobTransitioned(JobTransitioned event) {
		if (!"J9".equals(event.transition())
				|| !this.processedEvents.markProcessed("reputation.ReputationListeners.onJobTransitioned", event.eventId())) {
			return;
		}
		String days = event.facts().get("ratingWindowDays");
		this.posts.post(event.jobId()).filter(post -> post.mentorId() != null).ifPresent(post -> this.ratings.open(post.jobId(),
				post.productId(), post.customerId(), post.mentorId(), post.title(),
				days == null ? Ratings.DEFAULT_DAYS : Integer.parseInt(days), event.transitionId()));
	}

}
