package vn.vibefix.repo.internal;

import java.util.UUID;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import vn.vibefix.github.GitHubException;
import vn.vibefix.github.PlatformRepositories;
import vn.vibefix.job.JobBusy;
import vn.vibefix.operations.Lane;
import vn.vibefix.operations.OperationHandler;
import vn.vibefix.operations.OperationRun;
import vn.vibefix.operations.Outcome;
import vn.vibefix.repo.internal.JobGrants.Grant;

/**
 * {@value JobGrants#RAISE} (FR-75, AD-11): a Crew change member who accepted on an Active Job is raised
 * to write in place and read back, without changing the Job's state. Collaborators are looked up on use.
 */
@Component
class GrantRaiseOperation implements OperationHandler {

	private final ObjectProvider<JobGrants> grants;

	private final ObjectProvider<JobBusy> busy;

	private final ObjectProvider<PlatformRepositories> repositories;

	GrantRaiseOperation(ObjectProvider<JobGrants> grants, ObjectProvider<JobBusy> busy,
			ObjectProvider<PlatformRepositories> repositories) {
		this.grants = grants;
		this.busy = busy;
		this.repositories = repositories;
	}

	@Override
	public String type() {
		return JobGrants.RAISE;
	}

	@Override
	public Lane lane() {
		return Lane.GITHUB_SHORT;
	}

	@Override
	public Outcome run(OperationRun run) {
		Grant grant = this.grants.getObject().find(UUID.fromString(String.valueOf(run.payload().get("grantId")))).orElse(null);
		if (grant == null) {
			return new Outcome.Obsolete("GRANT_GONE");
		}
		if ("WRITE".equals(grant.status())) {
			return Outcome.succeeded();
		}
		boolean active = this.busy.getObject().job(grant.jobId()).map(job -> "ACTIVE".equals(job.state())).orElse(false);
		if (!active || !"ACCEPTED".equals(grant.status())) {
			// NFR-6: nobody holds write on a Job that is not Active.
			return new Outcome.Obsolete("JOB_NOT_ACTIVE");
		}
		try {
			PlatformRepositories repositories = this.repositories.getObject();
			// AD-10 "Logins by ID": the login GitHub uses now.
			String login = this.grants.getObject().currentLogin(grant, repositories).orElse(null);
			if (login == null) {
				return new Outcome.Aborted("GITHUB_ACCOUNT_GONE");
			}
			this.grants.getObject().renamed(grant, login);
			repositories.raise(grant.repository(), login);
			if (!"write".equals(repositories.role(grant.repository(), login))) {
				return new Outcome.Transient("WRITE_NOT_READ_BACK");
			}
		}
		catch (GitHubException failed) {
			return new Outcome.Transient(failed.status() == 0 ? "GITHUB_UNAVAILABLE" : "GITHUB_HTTP_" + failed.status());
		}
		this.grants.getObject().wrote(grant);
		return Outcome.succeeded();
	}

}
