package vn.vibefix.delivery.internal;

import java.util.Map;
import java.util.UUID;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import vn.vibefix.operations.Lane;
import vn.vibefix.operations.OperationHandler;
import vn.vibefix.operations.OperationRun;
import vn.vibefix.operations.Outcome;
import vn.vibefix.repo.HandoverBranches;
import vn.vibefix.repo.HandoverBranches.Unavailable;

/**
 * {@value HandoverPullCloses#TYPE} (FR-33, R26, AD-6): the consequence of M3 or M4 on GitHub. It reads the
 * handover's pull request first and leaves one GitHub shows merged as it is; otherwise it posts M3's or M4's
 * reason comment once, then sets the title to the current one plus "(lần {round})" and closes it in one PATCH.
 * Each step reads before it writes, so a retry posts no second comment and adds no second suffix. The
 * {@code handover/{handoverId}} branch is kept. Collaborators are looked up on use.
 */
@Component
class HandoverPullCloseOperation implements OperationHandler {

	private final ObjectProvider<HandoverBranches> branches;

	HandoverPullCloseOperation(ObjectProvider<HandoverBranches> branches) {
		this.branches = branches;
	}

	@Override
	public String type() {
		return HandoverPullCloses.TYPE;
	}

	@Override
	public Lane lane() {
		return Lane.GITHUB_SHORT;
	}

	@Override
	public Outcome run(OperationRun run) {
		Map<String, Object> payload = run.payload();
		UUID jobId = UUID.fromString(String.valueOf(payload.get("jobId")));
		UUID handoverId = UUID.fromString(String.valueOf(payload.get("handoverId")));
		int pull = ((Number) payload.get("pull")).intValue();
		int round = ((Number) payload.get("round")).intValue();
		String comment;
		switch (String.valueOf(payload.get("transition"))) {
			case "M3" -> comment = HandoverPullCloses.voidComment(handoverId);
			case "M4" -> comment = HandoverPullCloses.revisionComment(handoverId);
			default -> {
				return new Outcome.Aborted("UNKNOWN_TRANSITION");
			}
		}
		try {
			boolean closed = this.branches.getObject().close(jobId, handoverId, pull, round, comment);
			return new Outcome.Succeeded(Map.of("closed", closed));
		}
		catch (Unavailable unavailable) {
			return new Outcome.Transient("GITHUB_UNAVAILABLE");
		}
	}

}
