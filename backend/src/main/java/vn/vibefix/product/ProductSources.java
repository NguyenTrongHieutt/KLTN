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
 * <li>what the daily drift poll reads (FR-20, R7, Story 23.5b).</li>
 * </ul>
 * vibefix keeps no GitHub linking token (R31). Each read records what it found of the Source
 * App and the workflow as the Product's status, in a transaction of its own, opening or closing the Customer's to-dos
 * on a change (Story 23.5c): the check and the re-confirmation for their Source App and workflow part, the drift read,
 * and the contributor read when the binding refuses. A GitHub failure records nothing.
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

	/** The Source Repository as the Product registered it: its permanent ID and the name it is used by. */
	record Registered(UUID productId, UUID customerId, long repositoryGithubId, String repositoryFullName) {
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
	 * through the Platform App (J7's head read still does until Story 23.6).
	 * @throws vn.vibefix.shared.ApiException {@code NOT_FOUND} for no such Product
	 * @throws vn.vibefix.github.GitHubException when GitHub cannot answer now, or the Source App is not configured:
	 * nothing is known, so the poll tries again the next day
	 */
	Drift drift(UUID productId, String copiedSha);

}
