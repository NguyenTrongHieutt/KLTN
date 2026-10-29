package vn.vibefix.matching.internal;

import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import vn.vibefix.github.GitHubException;
import vn.vibefix.identity.CustomerRepositories;
import vn.vibefix.job.JobPosts;
import vn.vibefix.job.JobPosts.JobPostState;
import vn.vibefix.product.ProductDirectory;
import vn.vibefix.product.ProductDirectory.ProductInfo;
import vn.vibefix.shared.ApiException;

/**
 * FR-13's contributor check, read from GitHub before the bid's transaction (A-03, AD-4, AD-6): the Source
 * Repository's contributors (up to five GitHub pages, with the Customer's token) are never read under the bidder's
 * advisory lock or a Job row lock, and the result is passed into the bid, never read again under a lock, since a
 * GitHub fact is a hint. GitHub not answering fails closed: the bid then refuses with {@code CONTRIBUTORS_UNKNOWN}.
 */
@Component
class SourceContributors {

	/** What the read found. */
	enum Contribution {

		/** The bidder is not among the contributors. */
		NONE,

		/** The bidder contributed to the Source Repository. */
		CONTRIBUTOR,

		/** GitHub did not answer, or the Job is one no bid reaches: the bid refuses (fails closed). */
		UNKNOWN

	}

	private final JobPosts posts;

	private final ProductDirectory products;

	private final CustomerRepositories repositories;

	SourceContributors(JobPosts posts, ProductDirectory products, CustomerRepositories repositories) {
		this.posts = posts;
		this.products = products;
		this.repositories = repositories;
	}

	/**
	 * Whether {@code bidderId} contributed to the Job's Source Repository. A Job the bid refuses before this rule
	 * (missing, the bidder's own, hidden, no longer Open) asks GitHub nothing.
	 * @throws IllegalStateException inside a transaction: the read runs before the bid's (A-03)
	 */
	Contribution read(UUID jobId, UUID bidderId) {
		if (TransactionSynchronizationManager.isActualTransactionActive()) {
			throw new IllegalStateException("The contributor read runs before the bid's transaction, never under a lock");
		}
		Optional<JobPostState> post = this.posts.post(jobId);
		if (post.isEmpty() || bidderId.equals(post.get().customerId()) || post.get().hidden() || !post.get().open()) {
			return Contribution.UNKNOWN;
		}
		Optional<ProductInfo> product = this.products.find(post.get().productId());
		if (product.isEmpty()) {
			return Contribution.UNKNOWN;
		}
		String[] repository = product.get().repositoryFullName().split("/", 2);
		try {
			return this.repositories.hasContributor(post.get().customerId(), repository[0], repository[1], bidderId)
					? Contribution.CONTRIBUTOR : Contribution.NONE;
		}
		catch (GitHubException | ApiException unanswered) {
			return Contribution.UNKNOWN;
		}
	}

}
