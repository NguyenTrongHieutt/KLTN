package vn.vibefix.repo.internal;

import java.util.UUID;

import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

import vn.vibefix.github.PlatformRepositories;
import vn.vibefix.github.PlatformRepositories.PlatformRepository;
import vn.vibefix.product.ProductSources;
import vn.vibefix.product.ProductSources.SourceCheck;
import vn.vibefix.repo.internal.JobSetups.Setup;

/**
 * What the Final Merge checks before acting (FR-37, NFR-6, AD-11, SG-9), outside any transaction:
 * <ul>
 * <li>the Source Repository, read again by its permanent ID with the Customer's token: gone or private
 * routes to the ZIP Fallback; a token to renew or a lost admin right waits for the Customer;</li>
 * <li>every grant of the Mentor and the Crew read back as revoked, else it waits;</li>
 * <li>J9's guard: the Intermediary default branch's head is the head {@code repo} read back after its
 * last handover merge, or the J7 commit when it made none; anything else routes to the ZIP Fallback.</li>
 * </ul>
 * Each GitHub read throws {@link vn.vibefix.github.GitHubException} when GitHub fails.
 */
@Component
class FinalMergePreconditions {

	/** Where the preconditions stand. */
	enum Verdict {

		HOLD, WAIT_FOR_CUSTOMER, WAIT_FOR_REVOCATION, SOURCE_GONE, GUARD_FAILED

	}

	/**
	 * What the check read.
	 * @param head the Intermediary default branch's head
	 */
	record Read(Verdict verdict, @Nullable SourceCheck source, @Nullable Setup setup,
			@Nullable PlatformRepository intermediary, @Nullable String head) {
	}

	private final ProductSources sources;

	private final JobSetups setups;

	private final PlatformRepositories repositories;

	private final FinalMerges merges;

	FinalMergePreconditions(ProductSources sources, JobSetups setups, PlatformRepositories repositories,
			FinalMerges merges) {
		this.sources = sources;
		this.setups = setups;
		this.repositories = repositories;
		this.merges = merges;
	}

	Read read(UUID jobId, UUID productId) {
		SourceCheck source = this.sources.check(productId);
		if (source.block() == ProductSources.Block.SOURCE_NOT_PUBLIC) {
			return new Read(Verdict.SOURCE_GONE, source, null, null, null);
		}
		if (source.block() != null) {
			return new Read(Verdict.WAIT_FOR_CUSTOMER, source, null, null, null);
		}
		if (!this.merges.revoked(jobId)) {
			return new Read(Verdict.WAIT_FOR_REVOCATION, source, null, null, null);
		}
		Setup setup = this.setups.latest(jobId).filter(found -> "DONE".equals(found.status())).orElse(null);
		PlatformRepository intermediary = setup == null ? null : setup.intermediary();
		if (setup == null || intermediary == null || setup.approvedSha() == null || setup.defaultBranch() == null) {
			return new Read(Verdict.GUARD_FAILED, source, setup, null, null);
		}
		String head = this.repositories.refs(intermediary).get("refs/heads/" + setup.defaultBranch());
		boolean guard = head != null && head.equals(this.merges.expectedHead(jobId, setup.approvedSha()));
		return new Read(guard ? Verdict.HOLD : Verdict.GUARD_FAILED, source, setup, intermediary, head);
	}

}
