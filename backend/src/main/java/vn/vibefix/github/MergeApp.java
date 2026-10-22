package vn.vibefix.github;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import vn.vibefix.github.GitHubApps.InstallationToken;

/**
 * The Merge App's installations (AD-10, Story 8.7): each Customer installs it on their Source Repository
 * for one Final Merge, and GitHub allows one installation per account. Every read uses the App's JWT; the
 * selection is read with an unnarrowed Metadata-read token for that one installation, minted and revoked
 * here, so it never leaves {@code github}. Each Final Merge token comes from an installation and is
 * narrowed to one repository. Each call throws {@link GitHubException} when GitHub fails.
 */
public interface MergeApp {

	/** One selected repository: its numeric ID, its full name, and its owner's numeric ID. */
	record SelectedRepository(long id, String fullName, long ownerId) {
	}

	/**
	 * An installation as the App's JWT reads it.
	 * @param accountId the account it is installed on, a user or an organization
	 * @param all {@code repository_selection: all}, which AD-10 refuses; its repositories are not read
	 * @param suspended suspended by its account, so it cannot be used
	 */
	record Installation(long id, long accountId, String accountLogin, boolean all, boolean suspended,
			List<SelectedRepository> repositories) {

		public Installation {
			repositories = List.copyOf(repositories);
		}

	}

	/** An install request waiting for an organization owner (SG-21): its account and who asked. */
	record InstallRequest(long id, long accountId, String accountLogin, long requesterId) {
	}

	/** @return empty when GitHub does not know the installation (404) */
	Optional<Installation> installation(long installationId);

	/** The install requests waiting for an organization owner ({@code GET /app/installation-requests}). */
	List<InstallRequest> requests();

	/** Uninstalls ({@code DELETE /app/installations/{id}}); one already gone changes nothing. */
	void remove(long installationId);

	/** The install link, carrying {@code state} back to the Setup URL. */
	String installUrl(String state);

	/** A token from {@code installationId}, narrowed to {@code repositoryId} and {@code permissions}. */
	InstallationToken token(long installationId, long repositoryId, Map<String, String> permissions);

	/** Revokes the token at once. */
	void revoke(InstallationToken token);

	/** A pull request on the Source Repository: its number and its page. */
	record PullRequest(int number, String url) {
	}

	/**
	 * How a merge answered (SG-15).
	 * {@code REFUSED} is not transient: an archived repository, a ruleset, or required checks.
	 */
	enum MergeAnswer {

		MERGED, REFUSED, TRANSIENT

	}

	/**
	 * The trial merge's result.
	 * @param moved the Intermediary branch no longer has the head that was checked
	 */
	record TrialMerge(boolean clean, boolean moved, List<String> conflicts) {

		public TrialMerge {
			conflicts = List.copyOf(conflicts);
		}

	}

	/** The paths under {@code .github/workflows/} on {@code branch} (SG-19), with a Contents-read token. */
	List<String> workflowFiles(InstallationToken token, String fullName, String branch);

	/**
	 * Merges {@code intermediaryBranch} of the Intermediary Repository into the Source default branch in
	 * memory, in the sandbox, with nothing pushed (AD-11). Both are read as public repositories.
	 */
	TrialMerge trialMerge(String sourceFullName, String sourceBranch, String intermediaryFullName, String intermediaryBranch,
			String sha);

	/**
	 * Pushes {@code sha}, read on {@code intermediaryBranch}, to a new branch of the Source Repository,
	 * never forced, in the sandbox with the token.
	 * @return whether the push went through; one already there counts
	 */
	boolean push(InstallationToken token, String intermediaryFullName, String intermediaryBranch, String sha,
			String sourceFullName, String branch);

	/** The open pull request from {@code branch}, if one is. */
	Optional<PullRequest> findPull(InstallationToken token, String fullName, String branch);

	PullRequest openPull(InstallationToken token, String fullName, String branch, String base, String title, String body);

	/** Merges with a merge commit exactly at {@code sha}. */
	MergeAnswer mergePull(InstallationToken token, String fullName, int number, String sha, String title);

	/** Whether the pull request is merged, read back. */
	boolean pullMerged(InstallationToken token, String fullName, int number);

	/** Deletes the pushed branch; one already gone changes nothing. */
	void deleteBranch(InstallationToken token, String fullName, String branch);

}
