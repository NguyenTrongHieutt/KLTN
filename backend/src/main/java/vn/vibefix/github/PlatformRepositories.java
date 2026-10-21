package vn.vibefix.github;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.jspecify.annotations.Nullable;

/**
 * A Job's repositories in the platform organization (AD-11, AD-10): the Intermediary Repository, a copy
 * of the Source Repository at one approved commit, and the Working Repository, its fork inside the
 * organization. Every call mints a token for its one step, narrowed as AD-10 lists, and revokes it; the
 * copy itself runs in the sandbox (AD-32).
 * @throws GitHubException from every method when GitHub refuses or cannot be reached
 */
public interface PlatformRepositories {

	/** A repository in the platform organization. */
	record PlatformRepository(long id, String name, String fullName) {
	}

	/** The repository named {@code name} in the organization, if it exists. */
	Optional<PlatformRepository> find(String name);

	/** A new public repository, with Wiki, Issues, Projects, and Discussions off (the creation token). */
	PlatformRepository create(String name);

	/**
	 * Pushes exactly {@code sha} of the public {@code sourceFullName} to {@code branch} of {@code target},
	 * keeping the commit IDs: a bare clone and a push in the sandbox, with one token narrowed to
	 * {@code target}.
	 */
	void copy(String sourceFullName, String sha, String branch, PlatformRepository target);

	/** Forks {@code from} inside the organization as {@code name} (the fork token) and waits for GitHub's asynchronous fork. */
	PlatformRepository fork(PlatformRepository from, String name);

	/** Turns Wiki, Issues, Projects, and Discussions off, as a fork may not inherit it. */
	void turnFeaturesOff(PlatformRepository repository);

	/** How the organization differs from AD-11's settings (2FA required, Actions disabled, base permission none). */
	List<String> organizationDifferences();

	/** Creates AD-11's two rulesets on the Intermediary Repository, except those that exist by name. */
	void createRulesets(PlatformRepository intermediary);

	/** How the rulesets differ from AD-11's definition, the bypass actors read with an Administration write token. */
	List<String> rulesetDifferences(PlatformRepository intermediary);

	/**
	 * What inviting someone did (AD-11).
	 * @param invitationId the new invitation, or none when GitHub added the person at once (HTTP 204: they
	 * are an organization member, AD-11's {@code MEMBER_DRIFT})
	 * @param expiresAt when GitHub expires the invitation, 7 days after it was sent
	 */
	record Invited(@Nullable Long invitationId, @Nullable Instant expiresAt) {

		public boolean memberDrift() {
			return this.invitationId == null;
		}

	}

	/**
	 * Invites {@code login} with read permission. A pending invitation keeps the permission it was sent
	 * with, so the platform never raises or edits one (Spike S4).
	 */
	Invited invite(PlatformRepository repository, String login);

	/** Whether {@code login} is a collaborator now: their invitation was accepted. */
	boolean collaborator(PlatformRepository repository, String login);

	/** Raises an accepted collaborator to write in place, with no new invitation. */
	void raise(PlatformRepository repository, String login);

	/** The collaborator's role as GitHub reads it back ({@code read}, {@code write}, and so on), or {@code none}. */
	String role(PlatformRepository repository, String login);

	/** The lower-case logins of the organization's outside collaborators without two-factor authentication. */
	Set<String> withoutTwoFactor();

	/** Whether {@code login} is a member of the organization. */
	boolean organizationMember(String login);

	/** Removes a direct collaborator, as AD-11 does once a drifted member has left the organization. */
	void removeCollaborator(PlatformRepository repository, String login);

}
