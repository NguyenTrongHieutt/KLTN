package vn.vibefix.reputation.internal;

import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

import vn.vibefix.job.JobPosts;
import vn.vibefix.job.JobTransitioned;
import vn.vibefix.repo.DataDeletionConfirmed;
import vn.vibefix.shared.ProcessedEvents;

/**
 * J9 opens the Rating Window (FR-49, FR-78) for the Job's copied {@code RATING_WINDOW_DAYS}, which the
 * transition carries, and creates the Portfolio Entries of a Job with Accepted work, Closed or Cancelled (FR-50): J10
 * refuses a Job with Accepted work, which reaches Cancelled through Closing and J9. A person's
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
		if (!"J9".equals(event.transition())
				|| !this.processedEvents.markProcessed("reputation.ReputationListeners.onJobTransitioned", event.eventId())) {
			return;
		}
		String days = event.facts().get("ratingWindowDays");
		this.posts.post(event.jobId()).filter(post -> post.mentorId() != null).ifPresent(post -> {
			this.ratings.open(post.jobId(), post.productId(), post.customerId(), post.mentorId(), post.title(),
					days == null ? Ratings.DEFAULT_DAYS : Integer.parseInt(days), event.transitionId());
			// Closed or Cancelled (Story 21.3): J10 refuses a Job with Accepted work, which reaches Cancelled through
			// Closing and J9; a Job without Accepted work gets no entry.
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
