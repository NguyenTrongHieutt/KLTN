package vn.vibefix.dispute.internal;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

import vn.vibefix.dispute.internal.TeamIssueEndings.Outcome;
import vn.vibefix.dispute.internal.TeamIssues.Ending;
import vn.vibefix.job.JobTransitioned;
import vn.vibefix.reputation.RatingRemoved;
import vn.vibefix.shared.ProcessedEvents;

/**
 * Moot endings (AD-40, ending 4), replay-safe: an open share Team Issue whose Milestone became final without
 * a release (M7, or M8 with nothing released, J10's cascades included), and an open Rating issue whose
 * Rating was removed by another path. A lock ends nothing.
 */
@Component
class TeamIssueListeners {

	private static final Set<String> FINAL_TRANSITIONS = Set.of("M7", "M8", "J10");

	private final JdbcClient jdbc;

	private final TeamIssueEndings endings;

	private final ProcessedEvents processedEvents;

	TeamIssueListeners(JdbcClient jdbc, TeamIssueEndings endings, ProcessedEvents processedEvents) {
		this.jdbc = jdbc;
		this.endings = endings;
		this.processedEvents = processedEvents;
	}

	@ApplicationModuleListener
	void onJobTransitioned(JobTransitioned event) {
		if (!FINAL_TRANSITIONS.contains(event.transition())
				|| !this.processedEvents.markProcessed("dispute.TeamIssueListeners.onJobTransitioned", event.eventId())) {
			return;
		}
		List<UUID> open = this.jdbc.sql("""
				SELECT id FROM dispute.team_issue WHERE job_id = :job AND subject = 'SHARE' AND phase <> 'ENDED' ORDER BY raised_at, id
				""").param("job", event.jobId()).query(UUID.class).list();
		for (UUID issueId : open) {
			this.endings.end(issueId, null, Ending.MOOT,
					issue -> this.endings.finalWithoutRelease(issue) ? Outcome.NONE : null);
		}
	}

	@ApplicationModuleListener
	void onRatingRemoved(RatingRemoved event) {
		if (!this.processedEvents.markProcessed("dispute.TeamIssueListeners.onRatingRemoved", event.eventId())) {
			return;
		}
		this.jdbc.sql("SELECT id FROM dispute.team_issue WHERE rating_id = :rating AND phase <> 'ENDED'")
			.param("rating", event.ratingId())
			.query(UUID.class)
			.list()
			.forEach(issueId -> this.endings.end(issueId, null, Ending.MOOT, issue -> Outcome.NONE));
	}

}
