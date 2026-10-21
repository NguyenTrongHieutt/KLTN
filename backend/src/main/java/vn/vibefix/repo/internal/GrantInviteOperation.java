package vn.vibefix.repo.internal;

import java.util.UUID;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import vn.vibefix.github.GitHubException;
import vn.vibefix.github.PlatformRepositories;
import vn.vibefix.github.PlatformRepositories.Invited;
import vn.vibefix.operations.Lane;
import vn.vibefix.operations.OperationHandler;
import vn.vibefix.operations.OperationRun;
import vn.vibefix.operations.Outcome;
import vn.vibefix.repo.internal.JobGrants.Grant;

/**
 * {@value JobGrants#INVITE} (AD-11): one invitation to the Working Repository, with read permission, as a
 * consequence operation that never takes the busy marker. A 204 means GitHub added an organization
 * member at once ({@code MEMBER_DRIFT}). Collaborators are looked up on use.
 */
@Component
class GrantInviteOperation implements OperationHandler {

	private final ObjectProvider<JobGrants> grants;

	private final ObjectProvider<PlatformRepositories> repositories;

	GrantInviteOperation(ObjectProvider<JobGrants> grants, ObjectProvider<PlatformRepositories> repositories) {
		this.grants = grants;
		this.repositories = repositories;
	}

	@Override
	public String type() {
		return JobGrants.INVITE;
	}

	@Override
	public Lane lane() {
		return Lane.GITHUB_SHORT;
	}

	@Override
	public Outcome run(OperationRun run) {
		Grant grant = this.grants.getObject().find(UUID.fromString(String.valueOf(run.payload().get("grantId")))).orElse(null);
		int round = ((Number) run.payload().get("round")).intValue();
		if (grant == null || grant.renewals() != round || !"INVITING".equals(grant.status())) {
			return new Outcome.Obsolete("GRANT_MOVED_ON");
		}
		Invited invited;
		try {
			invited = this.repositories.getObject().invite(grant.repository(), grant.login());
		}
		catch (GitHubException failed) {
			return new Outcome.Transient(failed.status() == 0 ? "GITHUB_UNAVAILABLE" : "GITHUB_HTTP_" + failed.status());
		}
		if (invited.memberDrift()) {
			this.grants.getObject().drifted(grant);
		}
		else {
			this.grants.getObject().invited(grant, invited);
		}
		return Outcome.succeeded();
	}

}
