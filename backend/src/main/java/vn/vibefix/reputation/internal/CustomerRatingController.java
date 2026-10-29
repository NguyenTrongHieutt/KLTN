package vn.vibefix.reputation.internal;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.jspecify.annotations.Nullable;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import vn.vibefix.identity.CustomerNames;
import vn.vibefix.identity.CustomerNames.CustomerSince;
import vn.vibefix.identity.GitHubGate;
import vn.vibefix.job.JobPosts;
import vn.vibefix.reputation.RatingRole;
import vn.vibefix.reputation.RatingSummary;
import vn.vibefix.reputation.Reputation;
import vn.vibefix.shared.Access;
import vn.vibefix.shared.ApiException;
import vn.vibefix.shared.Caller;
import vn.vibefix.shared.CurrentCaller;
import vn.vibefix.shared.ErrorCode;
import vn.vibefix.shared.Gate;

/**
 * The Customer Rating of the Jobs "Tìm việc" lists (FR-12, AD-36): MEN-3's visible Rating summary of a listed Job's
 * Customer as Customer (the Mentors' Ratings of them, AD-33, Story 21.3), with their kind and when they joined ("Cá nhân · từ {month}", author decision 2026-10-21), and the
 * Rating summaries of a page of MEN-2 rows in one call (SG-43, Story 21.1), for any unlocked user with GitHub linked,
 * as "Tìm việc" (X1) needs, with no account role (Story 20.3); an Administrator finds nothing. The Customer's name
 * and account ID stay on the server: each summary is keyed by its Job.
 */
@RestController
@Access(Gate.SIGNED_IN)
class CustomerRatingController {

	/** The Jobs of one MEN-2 page (AD-36). */
	static final int MAX_POSTS = 20;

	/**
	 * @param customerKind {@code INDIVIDUAL} or {@code BUSINESS}; {@code null} before the Customer saved CUS-11
	 * @param customerJoinedAt when the Customer's account was created
	 */
	record RatingSummaryView(@Nullable BigDecimal average, int count, @Nullable String customerKind,
			@Nullable Instant customerJoinedAt) {
	}

	/** The visible Rating summary of one listed Job's Customer, keyed by the Job. */
	record PostCustomerRatingView(UUID postId, @Nullable BigDecimal average, int count) {
	}

	private final JobPosts posts;

	private final Reputation reputation;

	private final GitHubGate mentors;

	private final CurrentCaller callers;

	private final CustomerNames customers;

	CustomerRatingController(JobPosts posts, Reputation reputation, GitHubGate mentors, CurrentCaller callers,
			CustomerNames customers) {
		this.customers = customers;
		this.posts = posts;
		this.reputation = reputation;
		this.mentors = mentors;
		this.callers = callers;
	}

	@GetMapping("/api/v1/mentor/job-posts/{postId}/customer-rating")
	RatingSummaryView customerRating(@PathVariable UUID postId) {
		linkedReader();
		UUID customer = this.posts.listedCustomer(postId).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
		RatingSummary summary = this.reputation.visibleSummary(customer, RatingRole.CUSTOMER);
		CustomerSince since = this.customers.since(customer).orElse(null);
		return new RatingSummaryView(summary.average(), summary.count(), since == null ? null : since.kind(),
				since == null ? null : since.joinedAt());
	}

	/**
	 * MEN-2: the Customer Rating summaries of up to a page of listed Jobs, in the order asked; a Job not listed is
	 * left out, so the call tells nothing the list would not.
	 * @param postIds the rows' Job IDs
	 */
	@GetMapping("/api/v1/mentor/customer-ratings")
	List<PostCustomerRatingView> customerRatings(
			@RequestParam(name = "post", required = false) @Nullable List<UUID> postIds) {
		linkedReader();
		if (postIds == null || postIds.isEmpty()) {
			return List.of();
		}
		List<UUID> asked = postIds.stream().distinct().toList();
		if (asked.size() > MAX_POSTS) {
			throw new ApiException(ErrorCode.VALIDATION);
		}
		Map<UUID, UUID> customerOf = this.posts.listedCustomers(asked);
		Map<UUID, RatingSummary> summaries = this.reputation
			.visibleSummaries(customerOf.values().stream().distinct().toList(), RatingRole.CUSTOMER);
		List<PostCustomerRatingView> found = new ArrayList<>();
		for (UUID post : asked) {
			UUID customer = customerOf.get(post);
			if (customer != null) {
				RatingSummary summary = summaries.getOrDefault(customer, RatingSummary.NONE);
				found.add(new PostCustomerRatingView(post, summary.average(), summary.count()));
			}
		}
		return found;
	}

	/** An unlocked user with GitHub linked (X1, AD-19); an Administrator finds nothing. */
	private void linkedReader() {
		Caller caller = this.callers.get();
		if (!caller.isUser()) {
			throw new ApiException(ErrorCode.NOT_FOUND);
		}
		this.mentors.requireGitHubLinked(caller.requireAccountId());
	}

}
