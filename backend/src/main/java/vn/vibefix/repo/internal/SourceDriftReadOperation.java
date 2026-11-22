package vn.vibefix.repo.internal;

import java.util.UUID;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import vn.vibefix.github.GitHubException;
import vn.vibefix.operations.Lane;
import vn.vibefix.operations.OperationHandler;
import vn.vibefix.operations.OperationRun;
import vn.vibefix.operations.Outcome;

/**
 * {@value SourceDrift#READ} (FR-20, AD-12, Story 24.6): one read of a Job's Source Repository through the Customer's
 * Source App, which a Source App delivery requested, keyed by the Job and the delivery. It only reads, and every
 * warning it makes is made once, so trying it again is safe: a GitHub failure is retried under the default policy, and
 * a read that exhausts it lands in the Administrators' failed queue (FR-64), where it can be tried again. A Job drift
 * is no longer told for (ended, say) is obsolete. It never takes the busy marker.
 */
@Component
class SourceDriftReadOperation implements OperationHandler {

	private final ObjectProvider<SourceDrift> drift;

	SourceDriftReadOperation(ObjectProvider<SourceDrift> drift) {
		this.drift = drift;
	}

	@Override
	public String type() {
		return SourceDrift.READ;
	}

	@Override
	public Lane lane() {
		return Lane.GITHUB_SHORT;
	}

	@Override
	public Outcome run(OperationRun run) {
		UUID jobId = UUID.fromString(String.valueOf(run.payload().get("jobId")));
		try {
			return this.drift.getObject().check(jobId) ? Outcome.succeeded() : new Outcome.Obsolete("JOB_NOT_RUNNING");
		}
		catch (GitHubException failed) {
			return new Outcome.Transient(failed.status() == 0 ? "GITHUB_UNAVAILABLE" : "GITHUB_HTTP_" + failed.status());
		}
	}

}
