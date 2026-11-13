package vn.vibefix.repo.internal;

import java.time.Clock;
import java.time.Duration;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import vn.vibefix.github.GitHubException;
import vn.vibefix.github.PlatformRepositories;
import vn.vibefix.github.PlatformRepositories.PlatformRepository;
import vn.vibefix.operations.Lane;
import vn.vibefix.operations.OperationHandler;
import vn.vibefix.operations.OperationRun;
import vn.vibefix.operations.Outcome;
import vn.vibefix.repo.internal.JobGrants.Grant;

/**
 * {@value Revocations#REVOKE} (FR-21, AD-11): removes the person as a collaborator and any pending
 * invitation, then reads both back. Until GitHub shows no access, it tries again every 30 seconds,
 * deferred and not counted as an attempt, so it never ends in the failure queue; the 15-minute
 * {@link RevocationDelay} tells the Administrators instead. An erased Customer's read (Story 23.7) has no row left: its
 * payload names the Intermediary Repository, whose every collaborator and invitation it removes and reads back gone,
 * since its only grantee was that Customer. Collaborators are looked up on use.
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
			return run.payload().containsKey("repositoryId") ? cleared(run) : new Outcome.Obsolete("GRANT_GONE");
		}
		if ("REVOKED".equals(grant.status())) {
			return Outcome.succeeded();
		}
		if (!"REVOKING".equals(grant.status())) {
			return new Outcome.Obsolete("GRANT_NOT_REVOKING");
		}
		try {
			PlatformRepositories repositories = this.repositories.getObject();
			// AD-10 "Logins by ID": the login GitHub uses now; a GitHub account that is gone keeps the last one.
			String login = this.grants.getObject().currentLogin(grant, repositories).orElse(grant.login());
			repositories.removeCollaborator(grant.repository(), login);
			repositories.removeInvitation(grant.repository(), login);
			if (repositories.collaborator(grant.repository(), login) || repositories.pendingInvitees(grant.repository())
				.contains(login.toLowerCase(Locale.ROOT))) {
				return new Outcome.Deferred(this.clock.instant().plus(RETRY));
			}
		}
		catch (GitHubException failed) {
			return new Outcome.Deferred(this.clock.instant().plus(RETRY));
		}
		this.revocations.getObject().revoked(grant);
		return Outcome.succeeded();
	}

	/**
	 * The erased Customer's Intermediary Repository holds nobody: every collaborator and invitation is removed, then read
	 * back. A repository already deleted holds nobody either: GitHub refuses a token narrowed to it (422) or finds it no
	 * more (404), and there is nothing to revoke.
	 */
	private Outcome cleared(OperationRun run) {
		Map<String, Object> payload = run.payload();
		String fullName = String.valueOf(payload.get("repositoryName"));
		PlatformRepository repository = new PlatformRepository(((Number) payload.get("repositoryId")).longValue(),
				fullName.substring(fullName.indexOf('/') + 1), fullName);
		try {
			PlatformRepositories repositories = this.repositories.getObject();
			for (String login : repositories.directCollaborators(repository).keySet()) {
				repositories.removeCollaborator(repository, login);
			}
			for (String login : repositories.pendingInvitees(repository)) {
				repositories.removeInvitation(repository, login);
			}
			if (!repositories.directCollaborators(repository).isEmpty() || !repositories.pendingInvitees(repository).isEmpty()) {
				return new Outcome.Deferred(this.clock.instant().plus(RETRY));
			}
		}
		catch (GitHubException failed) {
			if (failed.status() != 404 && failed.status() != 422) {
				return new Outcome.Deferred(this.clock.instant().plus(RETRY));
			}
		}
		this.revocations.getObject()
			.erasedRevoked(UUID.fromString(String.valueOf(payload.get("grantId"))),
					UUID.fromString(String.valueOf(payload.get("jobId"))));
		return Outcome.succeeded();
	}

}
