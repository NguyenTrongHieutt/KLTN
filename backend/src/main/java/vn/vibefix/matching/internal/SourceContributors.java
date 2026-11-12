package vn.vibefix.matching.internal;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import vn.vibefix.identity.CustomerRepositories;
import vn.vibefix.job.JobPosts;
import vn.vibefix.job.JobPosts.JobPostState;
import vn.vibefix.product.ProductSources;

/**
 * FR-13's and FR-81's contributor check, read from GitHub before the bid's, the application's or the signature's
 * transaction (A-03, AD-4, AD-6): the Source Repository's contributors are read through the Customer's Source App
 * ({@link ProductSources#contributors}, Story 23.5b), never under the bidder's advisory lock or a Job row lock, and the
 * result is passed into the command, never read again under a lock, since a GitHub fact is a hint. The person's GitHub
 * ID comes from {@code identity}. A read that cannot say who contributed (the Source App missing or not on this
 * repository, GitHub refusing or unreachable) fails closed: the command then refuses with {@code CONTRIBUTORS_UNKNOWN},
 * and it is never read as "not a contributor".
 */
@Component
class SourceContributors {

	/** What the read found. */
	enum Contribution {

		/** The bidder is not among the contributors. */
		NONE,

		/** The bidder contributed to the Source Repository. */
		CONTRIBUTOR,

		/**
		 * The contributors could not be read through the Source App, or the Job is one no bid reaches: the bid refuses
		 * (fails closed).
		 */
		UNKNOWN

	}

	private final JobPosts posts;

	private final ProductSources sources;

	private final CustomerRepositories accounts;

	SourceContributors(JobPosts posts, ProductSources sources, CustomerRepositories accounts) {
		this.posts = posts;
		this.sources = sources;
		this.accounts = accounts;
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
		return contribution(post.get(), bidderId);
	}

	/**
	 * Whether {@code applicantId} contributed to the Job's Source Repository, for a Crew Application (FR-81, A-03):
	 * read the same way, before the application's transaction. A Job the application refuses before this rule
	 * (missing, hidden, the applicant's own as Customer or Mentor) asks GitHub nothing; the recruitment itself is read
	 * again under the Job row lock.
	 * @throws IllegalStateException inside a transaction
	 */
	Contribution readForCrew(UUID jobId, UUID applicantId) {
		if (TransactionSynchronizationManager.isActualTransactionActive()) {
			throw new IllegalStateException("The contributor read runs before the application's transaction, never under a lock");
		}
		Optional<JobPostState> post = this.posts.post(jobId);
		if (post.isEmpty() || applicantId.equals(post.get().customerId()) || applicantId.equals(post.get().mentorId())
				|| post.get().hidden()) {
			return Contribution.UNKNOWN;
		}
		return contribution(post.get(), applicantId);
	}

	/**
	 * Whether a picked person contributed to the Job's Source Repository, for their signature (FR-81, A-03): as
	 * {@link #readForCrew}, but a hidden Job Post asks them nothing different, since its hiding lapses pending applications
	 * only and a pick stands until it is signed or ends.
	 * @throws IllegalStateException inside a transaction
	 */
	Contribution readForSignature(UUID jobId, UUID accountId) {
		if (TransactionSynchronizationManager.isActualTransactionActive()) {
			throw new IllegalStateException("The contributor read runs before the signature's transaction, never under a lock");
		}
		Optional<JobPostState> post = this.posts.post(jobId);
		if (post.isEmpty() || accountId.equals(post.get().customerId()) || accountId.equals(post.get().mentorId())) {
			return Contribution.UNKNOWN;
		}
		return contribution(post.get(), accountId);
	}

	private Contribution contribution(JobPostState post, UUID accountId) {
		Long githubId = this.accounts.githubUserId(accountId).orElse(null);
		if (githubId == null) {
			// No GitHub account: none of its commits can name them.
			return Contribution.NONE;
		}
		Optional<Set<Long>> contributors = this.sources.contributors(post.productId());
		if (contributors.isEmpty()) {
			return Contribution.UNKNOWN;
		}
		return contributors.get().contains(githubId) ? Contribution.CONTRIBUTOR : Contribution.NONE;
	}

}
