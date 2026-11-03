package vn.vibefix.dispute.internal;

import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

import vn.vibefix.dispute.internal.TeamIssueEndings.Outcome;
import vn.vibefix.dispute.internal.TeamIssues.Ending;
import vn.vibefix.reputation.RatingRemoved;
import vn.vibefix.shared.ProcessedEvents;

/**
 * Moot endings (AD-40, ending 4), replay-safe: an open Team Issue whose Rating was removed by another path. A lock ends
 * nothing.
 */
@Component
class TeamIssueListeners {

	private final JdbcClient jdbc;

	private final TeamIssueEndings endings;

	private final ProcessedEvents processedEvents;

	TeamIssueListeners(JdbcClient jdbc, TeamIssueEndings endings, ProcessedEvents processedEvents) {
		this.jdbc = jdbc;
		this.endings = endings;
		this.processedEvents = processedEvents;
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
