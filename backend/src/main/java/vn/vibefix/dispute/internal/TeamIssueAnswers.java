package vn.vibefix.dispute.internal;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import vn.vibefix.audit.AuditActor;
import vn.vibefix.audit.AuditEntry;
import vn.vibefix.audit.AuditLog;
import vn.vibefix.audit.AuditObject;
import vn.vibefix.dispute.internal.TeamIssueEndings.Outcome;
import vn.vibefix.dispute.internal.TeamIssues.Answer;
import vn.vibefix.dispute.internal.TeamIssues.Ending;
import vn.vibefix.dispute.internal.TeamIssues.Phase;
import vn.vibefix.dispute.internal.TeamIssues.TeamIssue;
import vn.vibefix.identity.Administrators;
import vn.vibefix.job.TeamIssueLocks;
import vn.vibefix.job.TeamIssueLocks.TeamIssueJob;
import vn.vibefix.notification.Audience;
import vn.vibefix.notification.NotificationRequest;
import vn.vibefix.notification.Notifications;
import vn.vibefix.notification.Recipient;
import vn.vibefix.notification.TodoRequest;
import vn.vibefix.notification.Todos;
import vn.vibefix.shared.ApiException;
import vn.vibefix.shared.ErrorCode;
import vn.vibefix.timers.DeadlineRequest;
import vn.vibefix.timers.Deadlines;

/**
 * What the Mentor and the raiser do with a Team Issue (FR-79, AD-40), each under AD-4's locks. The Mentor answers once,
 * before the answer period's due time, keeping the Rating or withdrawing it; the answer changes nothing yet and starts
 * the escalation period. In that period, before its due time, the raiser accepts the answer (ending 1) or asks an
 * Administrator to look ("Nhờ quản trị viên xem xét"), which waits with no Deadline.
 */
@Component
class TeamIssueAnswers {

	static final String ANSWERED = "dispute.team_issue_answered";

	static final String ESCALATED = "dispute.team_issue_escalated";

	private final JdbcClient jdbc;

	private final TeamIssues issues;

	private final TeamIssueEndings endings;

	private final TeamIssueLocks locks;

	private final Administrators administrators;

	private final Deadlines deadlines;

	private final Notifications notifications;

	private final Todos todos;

	private final AuditLog auditLog;

	private final Clock clock;

	TeamIssueAnswers(JdbcClient jdbc, TeamIssues issues, TeamIssueEndings endings, TeamIssueLocks locks,
			Administrators administrators, Deadlines deadlines, Notifications notifications, Todos todos, AuditLog auditLog,
			Clock clock) {
		this.jdbc = jdbc;
		this.issues = issues;
		this.endings = endings;
		this.locks = locks;
		this.administrators = administrators;
		this.deadlines = deadlines;
		this.notifications = notifications;
		this.todos = todos;
		this.auditLog = auditLog;
		this.clock = clock;
	}

	/**
	 * The Mentor's one answer.
	 * @throws ApiException {@code NOT_FOUND} unless the issue is on the Mentor's Job; {@code CONFLICT ALREADY_ANSWERED};
	 * {@code GUARD_FAILED ANSWER_PERIOD_ENDED}
	 */
	@Transactional
	TeamIssue answer(UUID mentorId, UUID jobId, UUID issueId, Answer kind) {
		TeamIssue found = this.issues.find(issueId)
			.filter(issue -> issue.jobId().equals(jobId))
			.orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
		TeamIssueJob job = this.locks.lockForTeamIssue(jobId)
			.filter(locked -> mentorId.equals(locked.mentorId()))
			.orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
		TeamIssue issue = this.issues.lock(found.id()).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
		if (issue.answerKind() != null) {
			throw new ApiException(ErrorCode.CONFLICT, DisputeReason.ALREADY_ANSWERED);
		}
		Instant now = this.clock.instant();
		if (issue.phase() != Phase.ANSWER || !now.isBefore(issue.answerDueAt())) {
			throw new ApiException(ErrorCode.GUARD_FAILED, DisputeReason.ANSWER_PERIOD_ENDED);
		}
		Instant due = now.plus(Duration.ofDays(escalationDays(issue.id())));
		this.jdbc.sql("""
				UPDATE dispute.team_issue SET answer_kind = :kind, answered_at = :now, phase = 'ESCALATION', escalation_due_at = :due
				WHERE id = :id
				""")
			.param("kind", kind.name())
			.param("now", Timestamp.from(now))
			.param("due", Timestamp.from(due))
			.param("id", issue.id())
			.update();
		this.todos.close(TeamIssues.ANSWER_TODO, mentorId, issue.id());
		openEscalationPeriod(issue, due);
		if (issue.raiserId() != null) {
			this.notifications.send(new NotificationRequest(ANSWERED, issue.id(),
					List.of(new Recipient(issue.raiserId(), Audience.CREW_EVER_ACTIVE)), Map.of("job", job.title()), "/cong-viec?vai-tro=tham-gia",
					jobId, null));
		}
		this.auditLog.record(AuditEntry.of(AuditActor.user(mentorId), "dispute.team_issue_answered",
				AuditObject.of("dispute.team_issue", issue.id())).forJob(jobId));
		return this.issues.find(issue.id()).orElseThrow();
	}

	/**
	 * The escalation period's Deadline and the raiser's to-do, after an answer or when the answer period
	 * ended without one. The caller holds the locks.
	 */
	void openEscalationPeriod(TeamIssue issue, Instant due) {
		this.deadlines.schedule(new DeadlineRequest(TeamIssueEscalationDeadline.KEY, issue.id(),
				TeamIssues.generation(issue.id(), Phase.ESCALATION), issue.jobId(), due));
		if (issue.raiserId() != null) {
			this.todos.open(new TodoRequest(TeamIssues.DECIDE_TODO, issue.raiserId(), Audience.CREW_EVER_ACTIVE, issue.id(),
					Map.of(), "/cong-viec?vai-tro=tham-gia", due, issue.jobId()));
		}
	}

	/**
	 * "Chấp nhận" (ending 1): the answer is the outcome.
	 * @throws ApiException {@code NOT_FOUND} unless the reader raised it; {@code GUARD_FAILED NO_ANSWER} or
	 * {@code ESCALATION_PERIOD_NOT_OPEN}
	 */
	TeamIssue accept(UUID raiserId, UUID issueId) {
		this.endings.end(issueId, raiserId, Ending.ACCEPTED, issue -> {
			choosing(issue, raiserId);
			if (issue.answerKind() == null) {
				throw new ApiException(ErrorCode.GUARD_FAILED, DisputeReason.NO_ANSWER);
			}
			return new Outcome(issue.answerKind() == Answer.WITHDRAW_RATING);
		});
		return mine(issueId, raiserId);
	}

	/**
	 * "Nhờ quản trị viên xem xét": the issue waits for an Administrator with no Deadline (Story 12.3).
	 * @throws ApiException {@code NOT_FOUND} unless the reader raised it; {@code GUARD_FAILED
	 * ESCALATION_PERIOD_NOT_OPEN}
	 */
	@Transactional
	TeamIssue escalate(UUID raiserId, UUID issueId) {
		TeamIssue found = this.issues.find(issueId)
			.filter(issue -> raiserId.equals(issue.raiserId()))
			.orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
		TeamIssueJob job = this.locks.lockForTeamIssue(found.jobId()).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
		TeamIssue issue = this.issues.lock(found.id()).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
		choosing(issue, raiserId);
		this.jdbc.sql("UPDATE dispute.team_issue SET phase = 'ESCALATED', escalated_at = :now WHERE id = :id")
			.param("now", Timestamp.from(this.clock.instant()))
			.param("id", issue.id())
			.update();
		this.todos.close(TeamIssues.DECIDE_TODO, raiserId, issue.id());
		List<Recipient> recipients = new ArrayList<>();
		if (issue.mentorId() != null) {
			recipients.add(new Recipient(issue.mentorId(), Audience.MENTOR));
		}
		this.administrators.activeIds().forEach(admin -> recipients.add(new Recipient(admin, Audience.ADMIN)));
		if (!recipients.isEmpty()) {
			this.notifications.send(new NotificationRequest(ESCALATED, issue.id(), recipients, Map.of("job", job.title()),
					"/cong-viec/" + issue.jobId(), issue.jobId(), null));
		}
		this.auditLog.record(AuditEntry.of(AuditActor.user(raiserId), "dispute.team_issue_escalated",
				AuditObject.of("dispute.team_issue", issue.id())).forJob(issue.jobId()));
		return this.issues.find(issue.id()).orElseThrow();
	}

	/** The raiser chooses in the escalation period, before its due time. */
	private void choosing(TeamIssue issue, UUID raiserId) {
		if (!raiserId.equals(issue.raiserId())) {
			throw new ApiException(ErrorCode.NOT_FOUND);
		}
		Instant due = issue.escalationDueAt();
		if (issue.phase() != Phase.ESCALATION || due == null || !this.clock.instant().isBefore(due)) {
			throw new ApiException(ErrorCode.GUARD_FAILED, DisputeReason.ESCALATION_PERIOD_NOT_OPEN);
		}
	}

	private TeamIssue mine(UUID issueId, UUID raiserId) {
		return this.issues.find(issueId)
			.filter(issue -> raiserId.equals(issue.raiserId()))
			.orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
	}

	private int escalationDays(UUID issueId) {
		return this.jdbc.sql("SELECT escalation_days FROM dispute.team_issue WHERE id = :id")
			.param("id", issueId)
			.query(Integer.class)
			.single();
	}

}
