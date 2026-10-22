package vn.vibefix.dispute.internal;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
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
import vn.vibefix.dispute.internal.TeamIssues.Subject;
import vn.vibefix.dispute.internal.TeamIssues.TeamIssue;
import vn.vibefix.job.MilestoneStates;
import vn.vibefix.job.TeamIssueLocks;
import vn.vibefix.job.TeamIssueLocks.TeamIssueJob;
import vn.vibefix.notification.Audience;
import vn.vibefix.notification.NotificationRequest;
import vn.vibefix.notification.Notifications;
import vn.vibefix.notification.Recipient;
import vn.vibefix.notification.Todos;
import vn.vibefix.operations.ExternalOperations;
import vn.vibefix.operations.OperationRequest;
import vn.vibefix.payment.Ledger;
import vn.vibefix.payment.ShareHolds;
import vn.vibefix.reputation.CrewRatings;
import vn.vibefix.shared.AdminReadGrants;

/**
 * A Team Issue ends exactly once (AD-40), in its own transaction in AD-4's lock order: the Job row, the
 * Milestone's Team Issue rows, then the ledger through {@code payment.endHold}. The caller's check runs under
 * those locks and names the outcome; any ending of a share issue whose Milestone is already final without a
 * release is moot. A share ending executes through {@code payment.endHold}, a Rating removed through
 * {@code reputation.removeRating}, and both parties are told; an escalated issue's read grant ends 30 days
 * after a decision, at once after any other ending (AD-20). When {@code payment} refuses the execution,
 * the issue stays in its phase and the ending is requested as a {@value TeamIssueEndingOperation#TYPE}
 * operation, which lands in FR-64's list and alerts Administrators, who retry it there.
 */
@Component
class TeamIssueEndings {

	static final String DECIDED = "dispute.team_issue_decided";

	/** The reason a removed Rating's rater reads (FR-60). */
	static final String REMOVED_REASON = "Rút lại qua khiếu nại nội bộ";

	private static final Set<String> FINAL = Set.of("CANCELLED", "SETTLED");

	/** How long a decided issue's Administrator keeps reading (AD-20). */
	static final Duration GRANT_AFTER_DECISION = Duration.ofDays(30);

	/** What an ending moves: points from the Mentor's share to the raiser's, or the Rating removed. */
	record Outcome(int points, boolean removeRating) {

		static final Outcome NONE = new Outcome(0, false);

	}

	enum Result {

		/** The issue ended now. */
		ENDED,

		/** The check found nothing to do: the issue had ended, or left the phase the caller served. */
		UNCHANGED,

		/** {@code payment} refused the execution; the ending waits in FR-64's list. */
		QUEUED

	}

	private final JdbcClient jdbc;

	private final TeamIssues issues;

	private final TeamIssueLocks locks;

	private final MilestoneStates milestones;

	private final Ledger ledger;

	private final ShareHolds holds;

	private final CrewRatings ratings;

	private final Notifications notifications;

	private final Todos todos;

	private final ExternalOperations operations;

	private final AuditLog auditLog;

	private final List<AdminReadGrants> grants;

	private final Clock clock;

	private final TransactionTemplate own;

	TeamIssueEndings(JdbcClient jdbc, TeamIssues issues, TeamIssueLocks locks, MilestoneStates milestones, Ledger ledger,
			ShareHolds holds, CrewRatings ratings, Notifications notifications, Todos todos, ExternalOperations operations,
			AuditLog auditLog, List<AdminReadGrants> grants, Clock clock, PlatformTransactionManager transactionManager) {
		this.grants = grants;
		this.jdbc = jdbc;
		this.issues = issues;
		this.locks = locks;
		this.milestones = milestones;
		this.ledger = ledger;
		this.holds = holds;
		this.ratings = ratings;
		this.notifications = notifications;
		this.todos = todos;
		this.operations = operations;
		this.auditLog = auditLog;
		this.clock = clock;
		this.own = new TransactionTemplate(transactionManager);
		this.own.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
	}

	/**
	 * Ends the issue unless the check says otherwise; a refused execution is queued.
	 * @param check runs under the locks on the issue as it now is: the outcome, {@code null} for nothing to
	 * do, or an {@link vn.vibefix.shared.ApiException} for a refused command
	 */
	Result end(UUID issueId, @Nullable UUID actorId, Ending ending, Function<TeamIssue, @Nullable Outcome> check) {
		AtomicReference<Outcome> decided = new AtomicReference<>();
		AtomicReference<Ending> named = new AtomicReference<>(ending);
		try {
			return endNow(issueId, actorId, ending, check, decided, named);
		}
		catch (IllegalStateException refused) {
			Outcome outcome = decided.get();
			if (outcome == null) {
				throw refused;
			}
			this.own.executeWithoutResult(status -> this.operations.request(new OperationRequest(TeamIssueEndingOperation.TYPE,
					issueId, Map.of("teamIssueId", issueId.toString(), "ending", named.get().name(), "points", outcome.points(),
							"removeRating", outcome.removeRating()),
					null, null, 0)));
			return Result.QUEUED;
		}
	}

	/**
	 * Runs the ending in its own transaction.
	 * @throws IllegalStateException when {@code payment} refuses its execution; nothing is written then
	 */
	Result endNow(UUID issueId, @Nullable UUID actorId, Ending ending, Function<TeamIssue, @Nullable Outcome> check) {
		return endNow(issueId, actorId, ending, check, new AtomicReference<>(), new AtomicReference<>(ending));
	}

	private Result endNow(UUID issueId, @Nullable UUID actorId, Ending ending, Function<TeamIssue, @Nullable Outcome> check,
			AtomicReference<Outcome> decided, AtomicReference<Ending> named) {
		Result result = this.own.execute(status -> {
			TeamIssue found = this.issues.find(issueId).orElse(null);
			if (found == null) {
				return Result.UNCHANGED;
			}
			TeamIssueJob job = this.locks.lockForTeamIssue(found.jobId(), found.milestoneId(), false).orElse(null);
			TeamIssue issue = this.issues.lockRows(found).orElse(null);
			if (job == null || issue == null || issue.phase() == Phase.ENDED) {
				return Result.UNCHANGED;
			}
			Outcome outcome = check.apply(issue);
			if (outcome == null) {
				return Result.UNCHANGED;
			}
			Ending applied = ending;
			if (issue.subject() == Subject.SHARE && finalWithoutRelease(issue)) {
				applied = Ending.MOOT;
				outcome = Outcome.NONE;
			}
			decided.set(outcome);
			named.set(applied);
			execute(issue, job, applied, outcome, actorId);
			return Result.ENDED;
		});
		return result == null ? Result.UNCHANGED : result;
	}

	/** A share issue's Milestone became final without a release: M7, or M8 with nothing released (AD-40). */
	boolean finalWithoutRelease(TeamIssue issue) {
		if (issue.milestoneId() == null) {
			return false;
		}
		String state = this.milestones.statesOf(issue.jobId()).get(issue.milestoneId());
		return state != null && FINAL.contains(state) && this.ledger.releasedTotal(issue.milestoneId()) == 0;
	}

	private void execute(TeamIssue issue, TeamIssueJob job, Ending ending, Outcome outcome, @Nullable UUID actorId) {
		boolean removed = issue.subject() == Subject.RATING && outcome.removeRating();
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
				UPDATE dispute.team_issue SET phase = 'ENDED', ended_at = :now, ending = :ending, outcome_points = :points,
				    outcome_rating_removed = :removed
				WHERE id = :id
				""")
			.param("now", Timestamp.from(now))
			.param("ending", ending.name())
			.param("points", issue.subject() == Subject.SHARE ? outcome.points() : null)
			.param("removed", issue.subject() == Subject.RATING ? removed : null)
			.param("id", issue.id())
			.update();
		if (issue.subject() == Subject.SHARE) {
			this.holds.endHold(issue.id(), outcome.points());
		}
		else if (removed && issue.ratingId() != null) {
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
