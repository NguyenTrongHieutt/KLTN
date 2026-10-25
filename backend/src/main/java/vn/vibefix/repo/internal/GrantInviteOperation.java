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
 * consequence operation that never takes the busy marker. The invitee's login is resolved from their
 * numeric GitHub ID just before sending (AD-10 "Logins by ID"), so a renamed GitHub account is still the one
 * invited; a GitHub account that is gone cannot be invited. A 204 means GitHub added an organization
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
		JobGrants grants = this.grants.getObject();
		Invited invited;
		try {
			PlatformRepositories repositories = this.repositories.getObject();
			String login = grants.currentLogin(grant, repositories).orElse(null);
			if (login == null) {
				return new Outcome.Aborted("GITHUB_ACCOUNT_GONE");
			}
			grants.renamed(grant, login);
			invited = repositories.invite(grant.repository(), login);
		}
		catch (GitHubException failed) {
			return new Outcome.Transient(failed.status() == 0 ? "GITHUB_UNAVAILABLE" : "GITHUB_HTTP_" + failed.status());
		}
		Grant sent = grants.find(grant.id()).orElse(grant);
		if (invited.memberDrift()) {
			grants.drifted(sent);
		}
		else {
			grants.invited(sent, invited);
		}
		return Outcome.succeeded();
	}

}
