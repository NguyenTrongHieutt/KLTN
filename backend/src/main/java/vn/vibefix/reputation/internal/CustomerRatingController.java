package vn.vibefix.reputation.internal;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import org.jspecify.annotations.Nullable;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import vn.vibefix.identity.ActiveAccount;
import vn.vibefix.identity.CustomerNames;
import vn.vibefix.identity.CustomerNames.CustomerSince;
import vn.vibefix.job.JobPosts;
import vn.vibefix.reputation.RatingSummary;
import vn.vibefix.reputation.Reputation;
import vn.vibefix.shared.Access;
import vn.vibefix.shared.ApiException;
import vn.vibefix.shared.Caller;
import vn.vibefix.shared.CurrentCaller;
import vn.vibefix.shared.ErrorCode;
import vn.vibefix.shared.Gate;
import vn.vibefix.shared.Role;

/**
 * MEN-3's Customer Rating (FR-12): the visible Rating summary of an Open Job Post's Customer, with their
 * kind and when they joined ("Cá nhân · từ {month}", author decision 2026-10-21), for unlocked Mentors
 * only. The Customer's name and account ID stay on the server.
 */
@RestController
@Access(Gate.SIGNED_IN)
class CustomerRatingController {

	/**
	 * @param customerKind {@code INDIVIDUAL} or {@code BUSINESS}; {@code null} before the Customer saved CUS-11
	 * @param customerJoinedAt when the Customer's account was created
	 */
	record RatingSummaryView(@Nullable BigDecimal average, int count, @Nullable String customerKind,
			@Nullable Instant customerJoinedAt) {
	}

	private final JobPosts posts;

	private final Reputation reputation;

	private final ActiveAccount mentors;

	private final CurrentCaller callers;

	private final CustomerNames customers;

	CustomerRatingController(JobPosts posts, Reputation reputation, ActiveAccount mentors, CurrentCaller callers,
			CustomerNames customers) {
		this.customers = customers;
		this.posts = posts;
		this.reputation = reputation;
		this.mentors = mentors;
		this.callers = callers;
	}

	@GetMapping("/api/v1/mentor/job-posts/{postId}/customer-rating")
	RatingSummaryView customerRating(@PathVariable UUID postId) {
		Caller caller = this.callers.get();
		if (!caller.hasRole(Role.MENTOR)) {
			throw new ApiException(ErrorCode.NOT_FOUND);
		}
		this.mentors.requireActiveAccount(caller.requireAccountId());
		UUID customer = this.posts.openCustomer(postId).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
		RatingSummary summary = this.reputation.visibleSummary(customer);
		CustomerSince since = this.customers.since(customer).orElse(null);
		return new RatingSummaryView(summary.average(), summary.count(), since == null ? null : since.kind(),
				since == null ? null : since.joinedAt());
	}

}
