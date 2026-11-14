package vn.vibefix.github;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.jspecify.annotations.Nullable;

/**
 * A Job's repositories in the platform organization (AD-11, AD-10, D1, D5): the Intermediary Repository and
 * the Working Repository, two private copies of the Source Repository at one approved commit, which J7 copies on the
 * Customer's own GitHub Actions (Story 23.6b), never in vibefix's sandbox. There is no fork, and GitHub Free takes no
 * ruleset on a private repository, so "only the System writes the Intermediary" is read back from its collaborators
 * and invitations. Every call mints a token for its one step, narrowed as AD-10 lists, and revokes it. Nothing here
 * copies code: a handover's copy runs on the runner's GitHub Actions (AD-46, Story 23.8a), and vibefix only reads the
 * branch back and opens the pull request.
 * @throws GitHubException from every method when GitHub refuses or cannot be reached
 */
public interface PlatformRepositories {

	/** A repository in the platform organization. */
	record PlatformRepository(long id, String name, String fullName) {
	}

	/** The repository named {@code name} in the organization, if it exists. */
	Optional<PlatformRepository> find(String name);

	/**
	 * A new private repository, with Wiki, Issues, Projects, and Discussions off (the creation token). Forking
	 * stays off through the organization's private forking setting, and its Actions stay disabled through the
	 * organization's policy, which lets only the runner run.
	 */
	PlatformRepository create(String name);

	/**
	 * The runner (AD-46), the one repository of the organization whose GitHub Actions may run: empty when none is
	 * configured, which {@link #organizationDifferences()} reports, or when GitHub does not show it.
	 */
	Optional<PlatformRepository> runner();

	/** The one workflow the runner holds, a file name in its {@code .github/workflows/} (AD-45, AD-46). */
	String runnerWorkflow();

	/**
	 * How the organization differs from AD-11's settings: two-factor authentication required, base permission
	 * none, members never forking private repositories, and GitHub Actions on the runner only ("Selected
	 * repositories" listing {@code vibefix.github.runner-repository} alone, the organization's own actions, read
	 * tokens, and no pull request approvals). An unset runner is a difference.
	 */
	List<String> organizationDifferences();

	/**
	 * How the runner differs from AD-46 (deferred-work 634): private, holding only the published copy workflow
	 * in {@code .github/workflows/} of its default branch, as an accepted blob (AD-45, Story 23.4), with no
	 * collaborator and no invitation. Empty when no runner is configured, which {@link #organizationDifferences()}
	 * reports.
	 */
	List<String> runnerDifferences();

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
	 * with 409 when its head branch no longer holds {@code sha}. A pull request already merged changes nothing.
	 */
	void mergePullRequest(PlatformRepository repository, int number, String sha, String title);

	/** Whether the pull request is merged, read back from GitHub. */
	boolean pullRequestMerged(PlatformRepository repository, int number);

	/**
	 * A pull request as GitHub shows it now.
	 * @param state {@code open} or {@code closed}; a merged one is {@code closed} too
	 */
	record PullRequestState(int number, String state, boolean merged, String title) {
	}

	PullRequestState pullRequest(PlatformRepository repository, int number);

	/** The body of every comment on the pull request's conversation, oldest first, every page. */
	List<String> pullRequestComments(PlatformRepository repository, int number);

	/** Adds a comment to the pull request's conversation. */
	void commentOnPullRequest(PlatformRepository repository, int number, String body);

	/** Renames and closes the pull request in one PATCH (R26); its head branch stays. */
	void renameAndClosePullRequest(PlatformRepository repository, int number, String title);

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
	 * @param files every path a commit added, changed, removed, or renamed (both names), at most 300; a handover
	 * reads its full list from the copy run's report instead (FR-32, Story 23.8a), and this one is a hint
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

	/**
	 * The repository's settings read back against AD-11's: private, with Wiki, Issues, Projects, Discussions,
	 * and forking off, and GitHub Actions disabled ({@code GET /repos/{r}/actions/permissions} answers
	 * {@code enabled: false}; nothing sets it, the organization's policy disables it).
	 */
	List<String> settingsDifferences(PlatformRepository repository);

	/** How the organization's people differ: owners other than {@code operatorLogin}, and any member who is no owner. */
	List<String> organizationPeopleDifferences(@Nullable String operatorLogin);

	/** How the Platform App's installation differs from AD-10's: All repositories, not suspended. */
	List<String> installationDifferences();

	/**
	 * The lower-case logins behind the repository's recent events (SM-2, SG-22), each with the types of the events they
	 * caused, as GitHub names them ({@code PushEvent}, {@code MemberEvent}, and so on).
	 */
	Map<String, Set<String>> recentActors(PlatformRepository repository);

	/**
	 * The commit SHA {@code branch} of a private platform repository points at now
	 * ({@code GET /repos/{fullName}/git/ref/heads/{branch}}), such as the Working Repository's default branch that a
	 * handover reads, or both repositories read back after J7's copy, with a token narrowed to it holding
	 * {@code contents} read.
	 */
	String branchHead(PlatformRepository repository, String branch);

	/** Removes {@code login}'s pending invitation, if any (FR-21). */
	void removeInvitation(PlatformRepository repository, String login);

	/**
	 * The ZIP of the repository's {@code branch}, built in the sandbox from tree objects (FR-21, FR-38, AD-32),
	 * cloned with a token narrowed to the repository holding {@code contents} read.
	 */
	byte[] zip(PlatformRepository repository, String branch);

	/** Deletes the repository; a repository already gone changes nothing (FR-21). */
	void delete(PlatformRepository repository);

}
