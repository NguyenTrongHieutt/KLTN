package vn.vibefix.reputation.internal;

import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

import vn.vibefix.job.JobPosts;
import vn.vibefix.job.JobTransitioned;
import vn.vibefix.repo.DataDeletionConfirmed;
import vn.vibefix.shared.ProcessedEvents;

/**
 * J9 opens the Rating Window (FR-49, FR-78) for the Job's copied {@code RATING_WINDOW_DAYS}, which the
 * transition carries; J9 and J10 create the Portfolio Entries of a Job with Accepted work (FR-50); a person's
 * completed data-deletion confirmation publishes their entry (FR-66). Replay-safe.
 */
@Component
class ReputationListeners {

	private final Ratings ratings;

	private final Portfolios portfolios;

	private final JobPosts posts;

	private final ProcessedEvents processedEvents;

	ReputationListeners(Ratings ratings, Portfolios portfolios, JobPosts posts, ProcessedEvents processedEvents) {
		this.ratings = ratings;
		this.portfolios = portfolios;
		this.posts = posts;
		this.processedEvents = processedEvents;
	}

	@ApplicationModuleListener
	void onJobTransitioned(JobTransitioned event) {
		boolean finished = "J9".equals(event.transition());
		if (!(finished || "J10".equals(event.transition()))
				|| !this.processedEvents.markProcessed("reputation.ReputationListeners.onJobTransitioned", event.eventId())) {
			return;
		}
		String days = event.facts().get("ratingWindowDays");
		this.posts.post(event.jobId()).filter(post -> post.mentorId() != null).ifPresent(post -> {
			if (finished) {
				this.ratings.open(post.jobId(), post.productId(), post.customerId(), post.mentorId(), post.title(),
						days == null ? Ratings.DEFAULT_DAYS : Integer.parseInt(days), event.transitionId());
			}
			// Closed, or Cancelled by J9 or J10 (Story 21.3): a Job without Accepted work gets no entry.
			this.portfolios.create(post.jobId());
		});
	}

	@ApplicationModuleListener
	void onDataDeletionConfirmed(DataDeletionConfirmed event) {
		if (this.processedEvents.markProcessed("reputation.ReputationListeners.onDataDeletionConfirmed", event.eventId())) {
			this.portfolios.publish(event.jobId(), event.accountId());
		}
	}

}
