package vn.vibefix.dispute.internal;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import vn.vibefix.audit.AuditActor;
import vn.vibefix.audit.AuditEntry;
import vn.vibefix.audit.AuditLog;
import vn.vibefix.audit.AuditObject;
import vn.vibefix.dispute.internal.TeamIssueEndings.Outcome;
import vn.vibefix.dispute.internal.TeamIssueEndings.Result;
import vn.vibefix.dispute.internal.TeamIssues.Answer;
import vn.vibefix.dispute.internal.TeamIssues.Ending;
import vn.vibefix.dispute.internal.TeamIssues.Phase;
import vn.vibefix.dispute.internal.TeamIssues.Subject;
import vn.vibefix.dispute.internal.TeamIssues.TeamIssue;
import vn.vibefix.identity.Administrators;
import vn.vibefix.job.Crews;
import vn.vibefix.job.TeamIssueLocks;
import vn.vibefix.job.TeamIssueLocks.TeamIssueJob;
import vn.vibefix.notification.Audience;
import vn.vibefix.notification.NotificationRequest;
import vn.vibefix.notification.Notifications;
import vn.vibefix.notification.Recipient;
import vn.vibefix.notification.TodoRequest;
import vn.vibefix.notification.Todos;
import vn.vibefix.payment.ShareHolds;
import vn.vibefix.payment.ShareHolds.Transfer;
import vn.vibefix.shared.ApiException;
import vn.vibefix.shared.CrewSplit;
import vn.vibefix.shared.ErrorCode;
import vn.vibefix.timers.DeadlineRequest;
import vn.vibefix.timers.Deadlines;

/**
 * What the Mentor and the raiser do with a Team Issue (FR-79, AD-40), each under AD-4's locks. The Mentor
 * answers once, before the answer period's due time; the answer moves no money and starts the escalation
 * period. In that period, before its due time, the raiser accepts the answer (ending 1) or asks an
 * Administrator to look ("Nhờ quản trị viên xem xét"), which waits with no Deadline. A share transfer is
 * capped by the Mentor's pinned share less the transfers recorded on the Milestone and the points offered
 * in the answers of its other share issues still in the escalation period.
 */
@Component
class TeamIssueAnswers {

	static final String ANSWERED = "dispute.team_issue_answered";

	static final String ESCALATED = "dispute.team_issue_escalated";

	/** The split a share issue's screen previews from (AD-33): the base and the percentages the pin now gives. */
	record Split(long basisVnd, UUID mentorId, int mentorPercent, UUID raiserId, int raiserPercent, List<Integer> others) {

		/** The two parties' VND parts once {@code points} move from the Mentor to the raiser. */
		long[] parts(int points) {
			Map<UUID, Integer> shares = new HashMap<>();
			List<UUID> rest = new ArrayList<>();
			for (int percent : this.others) {
				UUID other = UUID.randomUUID();
				rest.add(other);
				shares.put(other, percent);
			}
			shares.put(this.mentorId, this.mentorPercent - points);
			shares.put(this.raiserId, this.raiserPercent + points);
			Map<UUID, Long> split = CrewSplit.split(this.basisVnd, shares, this.mentorId);
			return new long[] { split.getOrDefault(this.raiserId, 0L), split.getOrDefault(this.mentorId, 0L) };
		}

	}

	private final JdbcClient jdbc;

	private final TeamIssues issues;

	private final TeamIssueEndings endings;

	private final TeamIssueLocks locks;

	private final Crews crews;

	private final ShareHolds holds;

	private final Administrators administrators;

	private final Deadlines deadlines;

	private final Notifications notifications;

	private final Todos todos;

	private final AuditLog auditLog;

	private final Clock clock;

	TeamIssueAnswers(JdbcClient jdbc, TeamIssues issues, TeamIssueEndings endings, TeamIssueLocks locks, Crews crews,
			ShareHolds holds, Administrators administrators, Deadlines deadlines, Notifications notifications, Todos todos,
			AuditLog auditLog, Clock clock) {
		this.jdbc = jdbc;
		this.issues = issues;
		this.endings = endings;
		this.locks = locks;
		this.crews = crews;
		this.holds = holds;
		this.administrators = administrators;
		this.deadlines = deadlines;
		this.notifications = notifications;
		this.todos = todos;
		this.auditLog = auditLog;
		this.clock = clock;
	}

	/**
	 * The Mentor's one answer.
	 * @throws ApiException {@code NOT_FOUND} unless the issue is on the Mentor's Job; {@code VALIDATION} for an
	 * answer its subject does not take; {@code CONFLICT ALREADY_ANSWERED}; {@code GUARD_FAILED
	 * ANSWER_PERIOD_ENDED}; {@code GUARD_FAILED SHARE_TRANSFER_TOO_LARGE} with the {@code cap}
	 */
	@Transactional
	TeamIssue answer(UUID mentorId, UUID jobId, UUID issueId, Answer kind, @Nullable Integer points) {
		TeamIssue found = this.issues.find(issueId)
			.filter(issue -> issue.jobId().equals(jobId))
			.orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
		TeamIssueJob job = this.locks.lockForTeamIssue(jobId, found.milestoneId(), false)
			.filter(locked -> mentorId.equals(locked.mentorId()))
			.orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
		TeamIssue issue = this.issues.lockRows(found).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
		boolean share = issue.subject() == Subject.SHARE;
		boolean fits = kind == Answer.KEEP || (share ? kind == Answer.TRANSFER : kind == Answer.WITHDRAW_RATING);
		if (!fits || (kind == Answer.TRANSFER && (points == null || points < 1 || points > 100))) {
			throw new ApiException(ErrorCode.VALIDATION);
		}
		if (issue.answerKind() != null) {
			throw new ApiException(ErrorCode.CONFLICT, DisputeReason.ALREADY_ANSWERED);
		}
		Instant now = this.clock.instant();
		if (issue.phase() != Phase.ANSWER || !now.isBefore(issue.answerDueAt())) {
			throw new ApiException(ErrorCode.GUARD_FAILED, DisputeReason.ANSWER_PERIOD_ENDED);
		}
		int moved = kind == Answer.TRANSFER ? points : 0;
		if (moved > 0) {
			int cap = cap(issue);
			if (moved > cap) {
				throw new ApiException(ErrorCode.GUARD_FAILED, DisputeReason.SHARE_TRANSFER_TOO_LARGE, Map.of("cap", cap));
			}
		}
		Instant due = now.plus(Duration.ofDays(escalationDays(issue.id())));
		this.jdbc.sql("""
				UPDATE dispute.team_issue SET answer_kind = :kind, answer_points = :points, answered_at = :now,
				    phase = 'ESCALATION', escalation_due_at = :due
				WHERE id = :id
				""")
			.param("kind", kind.name())
			.param("points", moved)
			.param("now", Timestamp.from(now))
			.param("due", Timestamp.from(due))
			.param("id", issue.id())
			.update();
		this.todos.close(TeamIssues.ANSWER_TODO, mentorId, issue.id());
		openEscalationPeriod(issue, due);
		if (issue.raiserId() != null) {
			this.notifications.send(new NotificationRequest(ANSWERED, issue.id(),
					List.of(new Recipient(issue.raiserId(), Audience.CREW_EVER_ACTIVE)), Map.of("job", job.title()), "/cong-viec",
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
					Map.of(), "/cong-viec", due, issue.jobId()));
		}
	}

	/**
	 * "Chấp nhận" (ending 1): the answer is the outcome.
	 * @throws ApiException {@code NOT_FOUND} unless the reader raised it; {@code GUARD_FAILED NO_ANSWER},
	 * {@code ESCALATION_PERIOD_NOT_OPEN}, or {@code ENDING_REFUSED}
	 */
	TeamIssue accept(UUID raiserId, UUID issueId) {
		Result result = this.endings.end(issueId, raiserId, Ending.ACCEPTED, issue -> {
			choosing(issue, raiserId);
			if (issue.answerKind() == null) {
				throw new ApiException(ErrorCode.GUARD_FAILED, DisputeReason.NO_ANSWER);
			}
			return new Outcome(issue.answerPoints() == null ? 0 : issue.answerPoints(),
					issue.answerKind() == Answer.WITHDRAW_RATING);
		});
		if (result == Result.QUEUED) {
			throw new ApiException(ErrorCode.GUARD_FAILED, DisputeReason.ENDING_REFUSED);
		}
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
		TeamIssueJob job = this.locks.lockForTeamIssue(found.jobId(), found.milestoneId(), false)
			.orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
		TeamIssue issue = this.issues.lockRows(found).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
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

	/**
	 * How many points the Mentor may still move on the issue's Milestone: their pinned share less the
	 * recorded transfers and the points offered in the answers of its other share issues in the escalation
	 * period (AD-40); 0 for a Rating issue.
	 */
	int cap(TeamIssue issue) {
		if (issue.milestoneId() == null || issue.mentorId() == null) {
			return 0;
		}
		int pinned = this.crews.releaseTermsOf(issue.milestoneId())
			.map(terms -> terms.shares().getOrDefault(issue.mentorId(), 0))
			.orElse(0);
		int recorded = this.holds.transfersOf(issue.milestoneId()).stream().mapToInt(Transfer::points).sum();
		int offered = this.jdbc.sql("""
				SELECT coalesce(sum(answer_points), 0) FROM dispute.team_issue
				WHERE milestone_id = :milestone AND subject = 'SHARE' AND phase = 'ESCALATION' AND answer_kind = 'TRANSFER'
				  AND id <> :id
				""").param("milestone", issue.milestoneId()).param("id", issue.id()).query(Integer.class).single();
		return Math.max(0, pinned - recorded - offered);
	}

	/** The split a share issue's preview reads; empty for a Rating issue or a Milestone without a pin. */
	@Transactional(readOnly = true)
	Optional<Split> split(TeamIssue issue) {
		if (issue.milestoneId() == null || issue.mentorId() == null || issue.raiserId() == null) {
			return Optional.empty();
		}
		UUID mentor = issue.mentorId();
		UUID raiser = issue.raiserId();
		return this.crews.releaseTermsOf(issue.milestoneId()).map(terms -> {
			Map<UUID, Integer> shares = new HashMap<>(terms.shares());
			for (Transfer transfer : this.holds.transfersOf(issue.milestoneId())) {
				shares.merge(mentor, -transfer.points(), Integer::sum);
				shares.merge(transfer.raiserId(), transfer.points(), Integer::sum);
			}
			List<Integer> others = shares.entrySet()
				.stream()
				.filter(entry -> !entry.getKey().equals(mentor) && !entry.getKey().equals(raiser))
				.map(Map.Entry::getValue)
				.sorted()
				.toList();
			return new Split(this.holds.basisOf(issue.milestoneId()), mentor, shares.getOrDefault(mentor, 0), raiser,
					shares.getOrDefault(raiser, 0), others);
		});
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
