package vn.vibefix.dispute.internal;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import vn.vibefix.audit.AuditActor;
import vn.vibefix.audit.AuditEntry;
import vn.vibefix.audit.AuditLog;
import vn.vibefix.audit.AuditObject;
import vn.vibefix.dispute.internal.TeamIssues.Phase;
import vn.vibefix.dispute.internal.TeamIssues.TeamIssue;
import vn.vibefix.job.TeamIssueLocks;
import vn.vibefix.notification.Todos;
import vn.vibefix.timers.DeadlineHandler;
import vn.vibefix.timers.DeadlineKind;
import vn.vibefix.timers.DeadlineOutcome;
import vn.vibefix.timers.DueDeadline;

/**
 * {@value #KEY} (FR-79, AD-7, AD-40): the Team Issue's answer period, non-pausable, its generation the
 * issue and phase {@code ANSWER}. Once the issue left that phase it is {@code OBSOLETE}; otherwise, under
 * the Job row lock and then the issue's, the escalation period starts by the copied days and the Mentor's
 * to-do closes. What the escalation period does is Story 12.2's. Collaborators are looked up on use.
 */
@Component
class TeamIssueAnswerDeadline implements DeadlineHandler {

	static final String KEY = "dispute.team_issue_answer";

	static final DeadlineKind KIND = new DeadlineKind(KEY, false, true);

	private final JdbcClient jdbc;

	private final ObjectProvider<TeamIssues> issues;

	private final ObjectProvider<TeamIssueLocks> locks;

	private final ObjectProvider<Todos> todos;

	private final ObjectProvider<AuditLog> auditLog;

	private final Clock clock;

	TeamIssueAnswerDeadline(JdbcClient jdbc, ObjectProvider<TeamIssues> issues, ObjectProvider<TeamIssueLocks> locks,
			ObjectProvider<Todos> todos, ObjectProvider<AuditLog> auditLog, Clock clock) {
		this.jdbc = jdbc;
		this.issues = issues;
		this.locks = locks;
		this.todos = todos;
		this.auditLog = auditLog;
		this.clock = clock;
	}

	@Override
	public DeadlineKind kind() {
		return KIND;
	}

	@Override
	@Transactional
	public DeadlineOutcome handle(DueDeadline deadline) {
		TeamIssues issues = this.issues.getObject();
		TeamIssue found = issues.find(deadline.subjectId()).orElse(null);
		if (found == null || !deadline.generation().equals(TeamIssues.generation(found.id(), Phase.ANSWER))) {
			return DeadlineOutcome.OBSOLETE;
		}
		this.locks.getObject().lockForTeamIssue(found.jobId(), found.milestoneId(), false);
		TeamIssue issue = issues.lock(found.id()).orElse(null);
		if (issue == null || issue.phase() != Phase.ANSWER) {
			return DeadlineOutcome.OBSOLETE;
		}
		int days = this.jdbc.sql("SELECT escalation_days FROM dispute.team_issue WHERE id = :id")
			.param("id", issue.id())
			.query(Integer.class)
			.single();
		Instant now = this.clock.instant();
		this.jdbc.sql("UPDATE dispute.team_issue SET phase = 'ESCALATION', escalation_due_at = :due WHERE id = :id")
			.param("due", Timestamp.from(now.plus(Duration.ofDays(days))))
			.param("id", issue.id())
			.update();
		if (issue.mentorId() != null) {
			this.todos.getObject().close(TeamIssues.ANSWER_TODO, issue.mentorId(), issue.id());
		}
		this.auditLog.getObject().record(AuditEntry.of(AuditActor.system(), "dispute.team_issue_answer_period_ended",
				AuditObject.of("dispute.team_issue", issue.id())).forJob(issue.jobId()));
		return DeadlineOutcome.FIRED;
	}

}
