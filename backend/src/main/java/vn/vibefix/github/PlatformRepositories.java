package vn.vibefix.github;

import java.time.Instant;
import java.util.List;
import java.util.Map;
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

	/**
	 * As {@link #copy(String, String, String, PlatformRepository)}, with {@code sha} read on
	 * {@code sourceBranch} of the source and pushed to {@code branch} of the target, such as a Handover
	 * Commit to {@code handover/{handoverId}} (AD-11).
	 */
	void copy(String sourceFullName, String sha, String sourceBranch, String branch, PlatformRepository target);

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

	/**
	 * The numeric GitHub IDs of the repository's collaborators now (AD-10 "Logins by ID"), every page: an
	 * invitee among them accepted, whatever their login has become.
	 */
	Set<Long> collaboratorIds(PlatformRepository repository);

	/**
	 * The current login of the GitHub account with this numeric ID (AD-10 "Logins by ID"), read with the
	 * Platform App just before a call that names the person ({@code GET /user/{id}}).
	 * @return empty when GitHub has no such account any more
	 */
	Optional<String> login(long githubUserId);

	/** Raises an accepted collaborator to write in place, with no new invitation. */
	void raise(PlatformRepository repository, String login);

	/** The collaborator's role as GitHub reads it back ({@code read}, {@code write}, and so on), or {@code none}. */
	String role(PlatformRepository repository, String login);

	/**
	 * The organization's outside collaborators without two-factor authentication, every page: each numeric
	 * GitHub ID with its lower-case login.
	 */
	Map<Long, String> withoutTwoFactor();

	/** Whether {@code login} is a member of the organization. */
	boolean organizationMember(String login);

	/** Removes a direct collaborator, as AD-11 does once a drifted member has left the organization. */
	void removeCollaborator(PlatformRepository repository, String login);

	/** Every branch and tag head ({@code refs/heads/…}, {@code refs/tags/…}) and the object it points at (AD-12). */
	Map<String, String> refs(PlatformRepository repository);

	/**
	 * An open pull request, with the login and the numeric ID of the account that opened it.
	 * @param authorId {@code null} when GitHub named no account ID
	 */
	record PullRequest(int number, String authorLogin, @Nullable Long authorId) {
	}

	/** The Platform App's bot login, {@code {slug}[bot]}, lower-case: the System's actor on GitHub. */
	String botLogin();

	List<PullRequest> openPullRequests(PlatformRepository repository);

	/** Closes a pull request: one the System did not open (FR-19, SG-16), or its own after a handover failed. */
	void closePullRequest(PlatformRepository repository, int number);

	/**
	 * Opens the System's pull request from {@code head} to {@code base} in the same repository, or answers
	 * the one already opened from {@code head}, open or closed, so a retry opens none twice (AD-11).
	 * @return its number
	 */
	int openPullRequest(PlatformRepository repository, String head, String base, String title, String body);

	/**
	 * Merges the pull request exactly at {@code sha} with a merge commit (FR-33, AD-11); GitHub refuses it
	 * when the head moved. A pull request already merged changes nothing.
	 */
	void mergePullRequest(PlatformRepository repository, int number, String sha, String title);

	/** Whether the pull request is merged, read back from GitHub. */
	boolean pullRequestMerged(PlatformRepository repository, int number);

	/**
	 * One commit of a compared range (AD-33).
	 * @param message the first line of its message, at most 300 characters
	 * @param authorId the author's GitHub numeric ID; {@code null} when GitHub resolved no account
	 */
	record ComparedCommit(String sha, String message, @Nullable Long authorId, @Nullable String authorLogin) {
	}

	/**
	 * What {@code base...head} holds (FR-32, AD-18).
	 * @param commits oldest first, at most 250
	 * @param files every path a commit added, changed, removed, or renamed (both names), at most 300
	 * @param truncated whether GitHub cut either list
	 */
	record Comparison(List<ComparedCommit> commits, List<String> files, boolean truncated) {

		public Comparison {
			commits = List.copyOf(commits);
			files = List.copyOf(files);
		}

	}

	/** The commits and changed paths from {@code baseSha} to {@code headSha} of the repository. */
	Comparison compare(PlatformRepository repository, String baseSha, String headSha);

	/** The repository's direct collaborators, lower-case login to role ({@code read}, {@code write}, and so on). */
	Map<String, String> directCollaborators(PlatformRepository repository);

	/** The lower-case logins with a pending invitation. */
	Set<String> pendingInvitees(PlatformRepository repository);

	/** How the repository's features differ from AD-11's (Wiki, Issues, Projects, and Discussions off). */
	List<String> featureDifferences(PlatformRepository repository);

	/** How the organization's people differ: owners other than {@code operatorLogin}, and any member who is no owner. */
	List<String> organizationPeopleDifferences(@Nullable String operatorLogin);

	/** How the Platform App's installation differs from AD-10's: All repositories, not suspended. */
	List<String> installationDifferences();

	/** The lower-case logins behind the repository's recent events (SM-2, SG-22). */
	Set<String> recentActors(PlatformRepository repository);

	/** A Source Repository as GitHub shows it now, read by its permanent ID (FR-20). */
	record SourceRepository(long id, String fullName, String defaultBranch, boolean archived) {
	}

	/** The public repository with this ID, or none when it is gone or private to a public read. */
	Optional<SourceRepository> source(long githubId);

	/** The commits on {@code branch} after {@code baseSha}, oldest first, at most 250 (FR-20, FR-53). */
	List<String> commitsSince(String fullName, String baseSha, String branch);

	/** Removes {@code login}'s pending invitation, if any (FR-21). */
	void removeInvitation(PlatformRepository repository, String login);

	/** The ZIP of the repository's {@code branch}, built in the sandbox from tree objects (FR-21, FR-38, AD-32). */
	byte[] zip(PlatformRepository repository, String branch);

	/** Deletes the repository; a repository already gone changes nothing (FR-21). */
	void delete(PlatformRepository repository);

}
