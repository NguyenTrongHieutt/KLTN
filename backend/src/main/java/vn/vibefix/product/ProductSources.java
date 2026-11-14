package vn.vibefix.product;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.jspecify.annotations.Nullable;

/**
 * The Source Repository read through the Customer's Source App binding (Story 23.5), with a token narrowed to the one
 * repository after the selection rule holds on a fresh read, and revoked after use:
 * <ul>
 * <li>checked again before a Code Job starts and before the Final Merge (FR-18, AD-13, AD-45): still the registered
 * repository, still administered by the Customer's linked GitHub account, and holding a published {@code vibefix.yml}
 * on its default branch;</li>
 * <li>its contributors, for the guard of bids, Crew Applications and signatures (FR-13, FR-81, Story 23.5b), which
 * fails closed;</li>
 * <li>what the daily drift poll reads (FR-20, R7, Story 23.5b);</li>
 * <li>for J7 (Story 23.6a): the default branch's head the Customer confirms, and the installation and repository a
 * run of {@code vibefix.yml} is dispatched through ({@link vn.vibefix.github.ActionsRuns.Dispatch}).</li>
 * </ul>
 * vibefix keeps no GitHub linking token (R31). Each read records what it found of the Source
 * App and the workflow as the Product's status, in a transaction of its own, opening or closing the Customer's to-dos
 * on a change (Story 23.5c): the check and the re-confirmation for their Source App and workflow part, the drift read,
 * the head read and the dispatch target, and the contributor read when the binding refuses. A GitHub failure records
 * nothing.
 */
public interface ProductSources {

	/** Why the Source Repository may not be used now; each is something the Customer can fix. */
	enum Block {

		/** No bound installation reaches it, or it is suspended or lacks AD-10's permissions. */
		SOURCE_APP_MISSING,

		/** The installation selects all repositories, not this one, or another that is no live Product's. */
		SOURCE_APP_WRONG_REPOSITORY,

		/** No {@code .github/workflows/vibefix.yml} on the default branch. */
		WORKFLOW_MISSING,

		/** The default branch's {@code vibefix.yml} is no published version. */
		WORKFLOW_MISMATCH,

		/** The Customer no longer administers it. */
		NO_ADMIN_PERMISSION

	}

	/**
	 * What the check found, with the Source Repository as the Product registered it.
	 * @param block why it may not be used, or none
	 * @param defaultBranch the branch GitHub names as default, as read now
	 */
	record SourceCheck(@Nullable Block block, UUID productId, UUID customerId, String productName,
			String repositoryFullName, long repositoryGithubId, String defaultBranch) {
	}

	/**
	 * The Source Repository as the Product registered it: its permanent ID, the name it is used by, and its default
	 * branch as last recorded.
	 */
	record Registered(UUID productId, UUID customerId, long repositoryGithubId, String repositoryFullName,
			String defaultBranch) {
	}

	java.util.Optional<Registered> registered(UUID productId);

	/**
	 * The Customer re-confirms a renamed or transferred Source Repository (FR-20, AD-13): found by its new name
	 * through the Source App, it must keep the registered ID, pass the selection rule, be administered by them, and
	 * hold the published workflow. Only then is the new name recorded, so it becomes the Final Merge target.
	 * @return why it may not be used, or none once the new name is recorded
	 * @throws vn.vibefix.github.GitHubException when GitHub cannot answer now
	 */
	@Nullable Block reconfirm(UUID productId, String newFullName);

	/**
	 * Reads the Product's Source Repository now.
	 * @throws vn.vibefix.shared.ApiException {@code NOT_FOUND} for no such Product
	 * @throws vn.vibefix.github.GitHubException when GitHub cannot answer now: try again later
	 */
	SourceCheck check(UUID productId);

	/**
	 * The GitHub numeric IDs of the Source Repository's contributors (FR-13, FR-81, metadata only), read now: the
	 * binding is taken on a fresh read, then {@code GET /repos/{owner}/{name}/contributors} (at most
	 * {@link vn.vibefix.github.GitHubRepositories#MAX_CONTRIBUTOR_PAGES} pages) with a Metadata-read token narrowed to
	 * the repository, revoked afterwards. Never inside a transaction or under a lock (A-03).
	 * @return empty when who contributed is unknown, and the caller fails closed, never reading it as "not a
	 * contributor": no such Product; the binding does not hold ({@code SOURCE_APP_MISSING},
	 * {@code SOURCE_APP_WRONG_REPOSITORY}); GitHub refuses, a 404 included, or cannot be reached; or the Source App is
	 * not configured
	 */
	Optional<Set<Long>> contributors(UUID productId);

	/**
	 * What the daily drift poll reads of a Source Repository (FR-20, R7). The admin right is not read: it is no drift.
	 * @param block why the Source App cannot read it: {@link Block#SOURCE_APP_MISSING} or
	 * {@link Block#SOURCE_APP_WRONG_REPOSITORY}, and nothing else is read; none while the binding holds
	 * @param workflow why its {@code vibefix.yml} is no published version: {@link Block#WORKFLOW_MISSING} or
	 * {@link Block#WORKFLOW_MISMATCH}; none while it matches, or when there is a {@code block}. The rest is read anyway.
	 * @param fullName the repository's full name as GitHub shows it now; the registered one when there is a
	 * {@code block}
	 * @param archived GitHub's {@code archived}; false when there is a {@code block}
	 * @param commits the commits on the default branch after the Job's copied commit, oldest first, at most
	 * {@link #MAX_DRIFT_COMMITS} (the newest), {@code ["unknown"]} when the copied commit is gone from the branch's
	 * history; empty when there is a {@code block}
	 */
	record Drift(@Nullable Block block, @Nullable Block workflow, String fullName, boolean archived, List<String> commits) {

		public Drift {
			commits = List.copyOf(commits);
		}

	}

	/** How many commits a {@link Drift} keeps, as a Drift Warning stores them (FR-53). */
	int MAX_DRIFT_COMMITS = 250;

	/**
	 * Reads the Product's Source Repository for the daily drift poll (FR-20, R7): the binding is taken on a fresh read
	 * (so a Source transferred to an account with the App is bound there, and shows its new owner), then one token
	 * narrowed to the repository (Contents read, Metadata read), revoked after use, reads the repository, its
	 * {@code vibefix.yml} and the commits on its default branch after {@code copiedSha}. The drift poll no longer reads
	 * through the Platform App (J7's head read moves to {@link #head} in Story 23.6b).
	 * @throws vn.vibefix.shared.ApiException {@code NOT_FOUND} for no such Product
	 * @throws vn.vibefix.github.GitHubException when GitHub cannot answer now, or the Source App is not configured:
	 * nothing is known, so the poll tries again the next day
	 */
	Drift drift(UUID productId, String copiedSha);

	/**
	 * The Source Repository's default-branch head as read now (Story 23.6a).
	 * @param block why the Source App cannot read it: {@link Block#SOURCE_APP_MISSING} or
	 * {@link Block#SOURCE_APP_WRONG_REPOSITORY}; none while the binding holds
	 * @param sha the head commit, 40 hex; none when there is a {@code block}
	 * @param branch the default branch as GitHub names it now; the Product's recorded one when there is a {@code block}
	 * @param fullName the repository's full name as GitHub shows it now; the registered one when there is a {@code block}
	 */
	record Head(@Nullable Block block, @Nullable String sha, String branch, String fullName) {

		public Head {
			if ((block == null) == (sha == null)) {
				throw new IllegalArgumentException("A head is a commit, or a block");
			}
		}

	}

	/**
	 * Reads the Product's Source Repository's default-branch head now, the commit J7 asks the Customer to confirm (Story
	 * 23.6a): the binding is taken on a fresh read, then one Contents-read token narrowed to the repository, revoked
	 * after use, reads the repository (which must be the registered one), its {@code vibefix.yml}, and the branch's ref.
	 * What it found of the Source App and the workflow is recorded, best-effort; a {@code vibefix.yml} that is no
	 * published version blocks no head read.
	 * @return the head, or the binding's block, with no token minted
	 * @throws vn.vibefix.shared.ApiException {@code NOT_FOUND} for no such Product
	 * @throws vn.vibefix.github.GitHubException when GitHub cannot answer now, a 404 to the branch included, or the
	 * Source App is not configured: nothing is recorded
	 */
	Head head(UUID productId);

	/**
	 * Where a run of {@code vibefix.yml} is dispatched, as read now (Story 23.6a): what
	 * {@link vn.vibefix.github.ActionsRuns.Dispatch} names for {@link vn.vibefix.github.GitHubApps.App#SOURCE}.
	 * @param block why the Source App cannot reach it: {@link Block#SOURCE_APP_MISSING} or
	 * {@link Block#SOURCE_APP_WRONG_REPOSITORY}; none while the binding holds
	 * @param installationId the Customer's installation of the Source App that the binding holds; 0 when there is a
	 * {@code block}
	 * @param repositoryId the repository's permanent numeric ID, the registered one
	 * @param fullName the repository's full name as GitHub shows it now; the registered one when there is a {@code block}
	 * @param defaultBranch its default branch as GitHub names it now; the Product's recorded one when there is a
	 * {@code block}
	 */
	record DispatchTarget(@Nullable Block block, long installationId, long repositoryId, String fullName,
			String defaultBranch) {

		public DispatchTarget {
			if ((block == null) != (installationId > 0)) {
				throw new IllegalArgumentException("A dispatch target names its installation, or a block");
			}
		}

	}

	/**
	 * The installation and the repository a run of {@code vibefix.yml} is dispatched through, read now (Story 23.6a):
	 * the binding is taken on a fresh read, then one token narrowed to the repository (Contents read, Metadata read),
	 * revoked after use, reads the repository, which must be the registered one, and its {@code vibefix.yml}. What it
	 * found of the Source App and the workflow is recorded, best-effort; the dispatch checks the workflow file itself.
	 * @return the target, or the binding's block, with no token minted
	 * @throws vn.vibefix.shared.ApiException {@code NOT_FOUND} for no such Product
	 * @throws vn.vibefix.github.GitHubException when GitHub cannot answer now, or the Source App is not configured:
	 * nothing is recorded
	 */
	DispatchTarget dispatchTarget(UUID productId);

	/**
	 * The Source Repository's own {@code .github/workflows/} files, read now (FR-37, Story 23.9a): the Final Merge
	 * compares them with the Intermediary's, because a run's {@code GITHUB_TOKEN} can never push a workflow file.
	 * @param block why the Source App cannot read it: {@link Block#SOURCE_APP_MISSING} or
	 * {@link Block#SOURCE_APP_WRONG_REPOSITORY}; none while the binding holds
	 * @param files each file's path to its blob SHA, by path; empty when there is a {@code block}, and for a
	 * repository with no such folder
	 */
	record SourceWorkflows(@Nullable Block block, java.util.SortedMap<String, String> files) {

		public SourceWorkflows {
			files = java.util.Collections.unmodifiableSortedMap(new java.util.TreeMap<>(files));
		}

	}

	/**
	 * Reads the files under {@code .github/workflows/} on the Product's Source Repository's default branch now, with
	 * one Contents-read token narrowed to the repository and revoked after use. The binding is taken on a fresh read,
	 * and what it found of the Source App and the workflow is recorded, best-effort.
	 * @throws vn.vibefix.shared.ApiException {@code NOT_FOUND} for no such Product
	 * @throws vn.vibefix.github.GitHubException when GitHub cannot answer now, or the Source App is not configured
	 */
	SourceWorkflows workflowFiles(UUID productId);

	/**
	 * One branch of the Source Repository as read now (FR-37, Story 23.9a): what the Final Merge reads back after its
	 * run pushed {@code vibefix/final-{jobId}}, and what it reads before a dispatch, since the run refuses a branch
	 * that exists.
	 * @param block why the Source App cannot read it: {@link Block#SOURCE_APP_MISSING} or
	 * {@link Block#SOURCE_APP_WRONG_REPOSITORY}; none while the binding holds
	 * @param sha the branch's head commit, 40 hex; none when there is a {@code block}, and none when GitHub shows no
	 * such branch
	 */
	record SourceBranch(@Nullable Block block, @Nullable String sha) {
	}

	/**
	 * Reads one branch of the Product's Source Repository now, the same way {@link #head} reads the default branch's.
	 * A branch GitHub does not show is no failure: it answers no commit.
	 * @throws vn.vibefix.shared.ApiException {@code NOT_FOUND} for no such Product
	 * @throws vn.vibefix.github.GitHubException when GitHub cannot answer now, or the Source App is not configured
	 */
	SourceBranch branch(UUID productId, String branch);

}
