package vn.vibefix.dispute.internal;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import vn.vibefix.dispute.internal.TeamIssueEndings.Outcome;
import vn.vibefix.dispute.internal.TeamIssueEndings.Result;
import vn.vibefix.dispute.internal.TeamIssues.Answer;
import vn.vibefix.dispute.internal.TeamIssues.Ending;
import vn.vibefix.dispute.internal.TeamIssues.Phase;
import vn.vibefix.timers.DeadlineHandler;
import vn.vibefix.timers.DeadlineKind;
import vn.vibefix.timers.DeadlineOutcome;
import vn.vibefix.timers.DueDeadline;

/**
 * {@value #KEY} (FR-79, AD-7, AD-40): the Team Issue's escalation period, non-pausable, its generation the
 * issue and phase {@code ESCALATION}. When it passes with the issue still in that phase, the issue ends
 * (ending 2) with the Mentor's answer as the outcome, or with nothing moved and the Rating kept when there
 * was no answer; an issue that was accepted or escalated meanwhile leaves it {@code OBSOLETE}. A refused
 * execution is queued for Administrators and the Deadline is done. Collaborators are looked up on use.
 */
@Component
class TeamIssueEscalationDeadline implements DeadlineHandler {

	static final String KEY = "dispute.team_issue_escalation";

	static final DeadlineKind KIND = new DeadlineKind(KEY, false, true);

	private final ObjectProvider<TeamIssueEndings> endings;

	TeamIssueEscalationDeadline(ObjectProvider<TeamIssueEndings> endings) {
		this.endings = endings;
	}

	@Override
	public DeadlineKind kind() {
		return KIND;
	}

	@Override
	public DeadlineOutcome handle(DueDeadline deadline) {
		String generation = TeamIssues.generation(deadline.subjectId(), Phase.ESCALATION);
		if (!deadline.generation().equals(generation)) {
			return DeadlineOutcome.OBSOLETE;
		}
		Result result = this.endings.getObject()
			.end(deadline.subjectId(), null, Ending.LAPSED,
					issue -> issue.phase() != Phase.ESCALATION ? null
							: new Outcome(issue.answerPoints() == null ? 0 : issue.answerPoints(),
									issue.answerKind() == Answer.WITHDRAW_RATING));
		return result == Result.UNCHANGED ? DeadlineOutcome.OBSOLETE : DeadlineOutcome.FIRED;
	}

}
