package vn.vibefix.repo.internal;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import vn.vibefix.github.GitHubException;
import vn.vibefix.github.PlatformRepositories;
import vn.vibefix.job.JobBusy;
import vn.vibefix.job.JobBusy.BusyJob;
import vn.vibefix.operations.Lane;
import vn.vibefix.operations.OperationHandler;
import vn.vibefix.operations.OperationRun;
import vn.vibefix.operations.Outcome;
import vn.vibefix.repo.internal.JobGrants.Grant;
import vn.vibefix.repo.internal.JobGrants.Person;

/**
 * {@value JobGrants#VERIFY} (FR-18, AD-5, AD-11, NFR-6): the Verify stage as a precondition operation
 * under the busy marker ({@code JOB_VERIFY}). Each counted person is raised to write in place, with no
 * new invitation, and read back as write; only then does the resolving transaction apply J7. A read-back
 * that is not write is tried again, so the Job is never shown as Active before it. A frozen Job only
 * waits (AD-28). Collaborators are looked up on use.
 */
@Component
class JobVerifyOperation implements OperationHandler {

	static final Duration FROZEN_WAIT = Duration.ofMinutes(15);

	private final ObjectProvider<JobGrants> grants;

	private final ObjectProvider<JobBusy> busy;

	private final ObjectProvider<PlatformRepositories> repositories;

	private final Clock clock;

	JobVerifyOperation(ObjectProvider<JobGrants> grants, ObjectProvider<JobBusy> busy,
			ObjectProvider<PlatformRepositories> repositories, Clock clock) {
		this.grants = grants;
		this.busy = busy;
		this.repositories = repositories;
		this.clock = clock;
	}

	@Override
	public String type() {
		return JobGrants.VERIFY;
	}

	@Override
	public Lane lane() {
		return Lane.GITHUB_SHORT;
	}

	@Override
	public Outcome run(OperationRun run) {
		UUID jobId = UUID.fromString(String.valueOf(run.payload().get("jobId")));
		JobGrants grants = this.grants.getObject();
		BusyJob job = this.busy.getObject().job(jobId).orElse(null);
		if (job == null || !"STARTING".equals(job.state())
				|| job.busy().filter(marker -> marker.operationKey().equals(run.key())).isEmpty()) {
			return new Outcome.Obsolete("JOB_MOVED_ON");
		}
		if (job.frozen()) {
			return new Outcome.Deferred(this.clock.instant().plus(FROZEN_WAIT));
		}
		List<Person> people = grants.counted(job);
		List<Grant> all = grants.ofJob(jobId);
		PlatformRepositories repositories = this.repositories.getObject();
		try {
			for (Person person : people) {
				Grant grant = all.stream()
					.filter(found -> found.accountId().equals(person.accountId()) && found.ready())
					.findFirst()
					.orElse(null);
				if (grant == null) {
					return new Outcome.Aborted("GRANT_NOT_READY");
				}
				// AD-10 "Logins by ID": the login GitHub uses now.
				String login = grants.currentLogin(grant, repositories).orElse(null);
				if (login == null) {
					return new Outcome.Aborted("GITHUB_ACCOUNT_GONE");
				}
				grants.renamed(grant, login);
				if (!"WRITE".equals(grant.status())) {
					repositories.raise(grant.repository(), login);
				}
				if (!"write".equals(repositories.role(grant.repository(), login))) {
					return new Outcome.Transient("WRITE_NOT_READ_BACK");
				}
				grants.wrote(grant);
			}
		}
		catch (GitHubException failed) {
			return new Outcome.Transient(failed.status() == 0 ? "GITHUB_UNAVAILABLE" : "GITHUB_HTTP_" + failed.status());
		}
		grants.verified(jobId, run.key());
		return Outcome.succeeded();
	}

}
