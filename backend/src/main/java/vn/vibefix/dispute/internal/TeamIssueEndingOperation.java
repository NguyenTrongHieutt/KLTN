package vn.vibefix.dispute.internal;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import vn.vibefix.dispute.internal.TeamIssueEndings.Outcome;
import vn.vibefix.dispute.internal.TeamIssueEndings.Result;
import vn.vibefix.dispute.internal.TeamIssues.Ending;
import vn.vibefix.dispute.internal.TeamIssues.Phase;
import vn.vibefix.operations.Lane;
import vn.vibefix.operations.OperationHandler;
import vn.vibefix.operations.OperationPolicy;
import vn.vibefix.operations.OperationRun;
import vn.vibefix.operations.Outcome.Succeeded;
import vn.vibefix.operations.Outcome.Transient;

/**
 * {@value #TYPE} (AD-40, AD-5, FR-64): a Team Issue ending that {@code payment} refused to execute. It has one
 * attempt, so a refusal lands in the failed-operations list and alerts Administrators; an Administrator's
 * retry runs the same ending again, which writes nothing once the issue has ended. Collaborators are looked
 * up on use.
 */
@Component
class TeamIssueEndingOperation implements OperationHandler {

	static final String TYPE = "dispute.team_issue_ending";

	private final ObjectProvider<TeamIssueEndings> endings;

	TeamIssueEndingOperation(ObjectProvider<TeamIssueEndings> endings) {
		this.endings = endings;
	}

	@Override
	public String type() {
		return TYPE;
	}

	@Override
	public Lane lane() {
		return Lane.VNPAY;
	}

	@Override
	public OperationPolicy policy() {
		return new OperationPolicy(1, Duration.ofMinutes(1));
	}

	@Override
	public vn.vibefix.operations.Outcome run(OperationRun run) {
		Map<String, Object> payload = run.payload();
		UUID issueId = UUID.fromString(String.valueOf(payload.get("teamIssueId")));
		Ending ending = Ending.valueOf(String.valueOf(payload.get("ending")));
		Outcome outcome = new Outcome(((Number) payload.get("points")).intValue(),
				Boolean.parseBoolean(String.valueOf(payload.get("removeRating"))));
		try {
			Result result = this.endings.getObject()
				.endNow(issueId, null, ending, issue -> issue.phase() == Phase.ENDED ? null : outcome);
			return new Succeeded(Map.of("result", result.name()));
		}
		catch (IllegalStateException refused) {
			return new Transient("ENDING_REFUSED");
		}
	}

}
