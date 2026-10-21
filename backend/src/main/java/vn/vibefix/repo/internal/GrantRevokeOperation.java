package vn.vibefix.repo.internal;

import java.time.Clock;
import java.time.Duration;
import java.util.Locale;
import java.util.UUID;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import vn.vibefix.github.GitHubException;
import vn.vibefix.github.PlatformRepositories;
import vn.vibefix.operations.Lane;
import vn.vibefix.operations.OperationHandler;
import vn.vibefix.operations.OperationRun;
import vn.vibefix.operations.Outcome;
import vn.vibefix.repo.internal.JobGrants.Grant;

/**
 * {@value Revocations#REVOKE} (FR-21, AD-11): removes the person as a collaborator and any pending
 * invitation, then reads both back. Until GitHub shows no access, it tries again every 30 seconds,
 * deferred and not counted as an attempt, so it never ends in the failure queue; the 15-minute
 * {@link RevocationDelay} tells the Administrators instead. Collaborators are looked up on use.
 */
@Component
class GrantRevokeOperation implements OperationHandler {

	/** FR-21: a revocation GitHub has not confirmed is tried again this often. */
	static final Duration RETRY = Duration.ofSeconds(30);

	private final ObjectProvider<JobGrants> grants;

	private final ObjectProvider<Revocations> revocations;

	private final ObjectProvider<PlatformRepositories> repositories;

	private final Clock clock;

	GrantRevokeOperation(ObjectProvider<JobGrants> grants, ObjectProvider<Revocations> revocations,
			ObjectProvider<PlatformRepositories> repositories, Clock clock) {
		this.grants = grants;
		this.revocations = revocations;
		this.repositories = repositories;
		this.clock = clock;
	}

	@Override
	public String type() {
		return Revocations.REVOKE;
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
		if ("REVOKED".equals(grant.status())) {
			return Outcome.succeeded();
		}
		if (!"REVOKING".equals(grant.status())) {
			return new Outcome.Obsolete("GRANT_NOT_REVOKING");
		}
		try {
			PlatformRepositories repositories = this.repositories.getObject();
			repositories.removeCollaborator(grant.repository(), grant.login());
			repositories.removeInvitation(grant.repository(), grant.login());
			if (repositories.collaborator(grant.repository(), grant.login()) || repositories.pendingInvitees(grant.repository())
				.contains(grant.login().toLowerCase(Locale.ROOT))) {
				return new Outcome.Deferred(this.clock.instant().plus(RETRY));
			}
		}
		catch (GitHubException failed) {
			return new Outcome.Deferred(this.clock.instant().plus(RETRY));
		}
		this.revocations.getObject().revoked(grant);
		return Outcome.succeeded();
	}

}
