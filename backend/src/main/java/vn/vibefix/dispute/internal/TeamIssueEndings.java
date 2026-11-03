package vn.vibefix.dispute.internal;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;

import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import vn.vibefix.audit.AuditActor;
import vn.vibefix.audit.AuditEntry;
import vn.vibefix.audit.AuditLog;
import vn.vibefix.audit.AuditObject;
import vn.vibefix.dispute.internal.TeamIssues.Ending;
import vn.vibefix.dispute.internal.TeamIssues.Phase;
import vn.vibefix.dispute.internal.TeamIssues.TeamIssue;
import vn.vibefix.job.TeamIssueLocks;
import vn.vibefix.job.TeamIssueLocks.TeamIssueJob;
import vn.vibefix.notification.Audience;
import vn.vibefix.notification.NotificationRequest;
import vn.vibefix.notification.Notifications;
import vn.vibefix.notification.Recipient;
import vn.vibefix.notification.Todos;
import vn.vibefix.reputation.CrewRatings;
import vn.vibefix.shared.AdminReadGrants;

/**
 * A Team Issue ends exactly once (AD-40), in one transaction of its own in AD-4's lock order: the Job row, then the
 * issue's row. The caller's check runs under those locks and names the outcome. A Rating removed goes through
 * {@code reputation.removeRating}, and both parties are told; an escalated issue's read grant ends 30 days after a
 * decision, at once after any other ending (AD-20). No money moves.
 */
@Component
class TeamIssueEndings {

	static final String DECIDED = "dispute.team_issue_decided";

	/** The reason a removed Rating's rater reads (FR-60). */
	static final String REMOVED_REASON = "Rút lại qua khiếu nại nội bộ";

	/** How long a decided issue's Administrator keeps reading (AD-20). */
	static final Duration GRANT_AFTER_DECISION = Duration.ofDays(30);

	/** What an ending does: remove the Rating, or keep it. */
	record Outcome(boolean removeRating) {

		static final Outcome NONE = new Outcome(false);

	}

	enum Result {

		/** The issue ended now. */
		ENDED,

		/** The check found nothing to do: the issue had ended, or left the phase the caller served. */
		UNCHANGED

	}

	private final JdbcClient jdbc;

	private final TeamIssues issues;

	private final TeamIssueLocks locks;

	private final CrewRatings ratings;

	private final Notifications notifications;

	private final Todos todos;

	private final AuditLog auditLog;

	private final List<AdminReadGrants> grants;

	private final Clock clock;

	private final TransactionTemplate own;

	TeamIssueEndings(JdbcClient jdbc, TeamIssues issues, TeamIssueLocks locks, CrewRatings ratings,
			Notifications notifications, Todos todos, AuditLog auditLog, List<AdminReadGrants> grants, Clock clock,
			PlatformTransactionManager transactionManager) {
		this.grants = grants;
		this.jdbc = jdbc;
		this.issues = issues;
		this.locks = locks;
		this.ratings = ratings;
		this.notifications = notifications;
		this.todos = todos;
		this.auditLog = auditLog;
		this.clock = clock;
		this.own = new TransactionTemplate(transactionManager);
		this.own.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
	}

	/**
	 * Ends the issue in its own transaction unless the check says otherwise.
	 * @param check runs under the locks on the issue as it now is: the outcome, {@code null} for nothing to
	 * do, or an {@link vn.vibefix.shared.ApiException} for a refused command
	 */
	Result end(UUID issueId, @Nullable UUID actorId, Ending ending, Function<TeamIssue, @Nullable Outcome> check) {
		Result result = this.own.execute(status -> {
			TeamIssue found = this.issues.find(issueId).orElse(null);
			if (found == null) {
				return Result.UNCHANGED;
			}
			TeamIssueJob job = this.locks.lockForTeamIssue(found.jobId()).orElse(null);
			TeamIssue issue = this.issues.lock(found.id()).orElse(null);
			if (job == null || issue == null || issue.phase() == Phase.ENDED) {
				return Result.UNCHANGED;
			}
			Outcome outcome = check.apply(issue);
			if (outcome == null) {
				return Result.UNCHANGED;
			}
			execute(issue, job, ending, outcome, actorId);
			return Result.ENDED;
		});
		return result == null ? Result.UNCHANGED : result;
	}

	private void execute(TeamIssue issue, TeamIssueJob job, Ending ending, Outcome outcome, @Nullable UUID actorId) {
		Instant now = this.clock.instant();
		UUID grant = this.jdbc.sql("SELECT grant_id FROM dispute.team_issue WHERE id = :id AND grant_id IS NOT NULL")
			.param("id", issue.id())
			.query(UUID.class)
			.optional()
			.orElse(null);
		if (grant != null) {
			Instant ends = ending == Ending.DECIDED ? now.plus(GRANT_AFTER_DECISION) : now;
			this.grants.forEach(module -> module.endAdminRead(grant, ends));
		}
		this.jdbc.sql("""
				UPDATE dispute.team_issue SET phase = 'ENDED', ended_at = :now, ending = :ending,
				    outcome_rating_removed = :removed
				WHERE id = :id
				""")
			.param("now", Timestamp.from(now))
			.param("ending", ending.name())
			.param("removed", outcome.removeRating())
			.param("id", issue.id())
			.update();
		if (outcome.removeRating()) {
			this.ratings.removeRating(issue.ratingId(), REMOVED_REASON);
		}
		if (issue.mentorId() != null) {
			this.todos.close(TeamIssues.ANSWER_TODO, issue.mentorId(), issue.id());
		}
		if (issue.raiserId() != null) {
			this.todos.close(TeamIssues.DECIDE_TODO, issue.raiserId(), issue.id());
		}
		List<Recipient> recipients = new ArrayList<>();
		if (issue.mentorId() != null) {
			recipients.add(new Recipient(issue.mentorId(), Audience.MENTOR));
		}
		if (issue.raiserId() != null) {
			recipients.add(new Recipient(issue.raiserId(), Audience.CREW_EVER_ACTIVE));
		}
		if (!recipients.isEmpty()) {
			this.notifications.send(new NotificationRequest(DECIDED, issue.id(), recipients, Map.of("job", job.title()),
					"/cong-viec/" + issue.jobId(), issue.jobId(), null));
		}
		this.auditLog.record(AuditEntry
			.of(actorId == null ? AuditActor.system() : AuditActor.user(actorId), "dispute.team_issue_ended",
					AuditObject.of("dispute.team_issue", issue.id()))
			.forJob(issue.jobId()));
	}

}
