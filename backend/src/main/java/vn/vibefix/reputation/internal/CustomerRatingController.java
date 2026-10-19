package vn.vibefix.reputation.internal;

import java.math.BigDecimal;
import java.util.UUID;

import org.jspecify.annotations.Nullable;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import vn.vibefix.identity.MentorGate;
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
 * MEN-3's Customer Rating (FR-12): the visible Rating summary of an Open Job Post's Customer, for
 * approved Mentors only. The Customer's account ID stays on the server.
 */
@RestController
@Access(Gate.SIGNED_IN)
class CustomerRatingController {

	record RatingSummaryView(@Nullable BigDecimal average, int count) {
	}

	private final JobPosts posts;

	private final Reputation reputation;

	private final MentorGate mentors;

	private final CurrentCaller callers;

	CustomerRatingController(JobPosts posts, Reputation reputation, MentorGate mentors, CurrentCaller callers) {
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
		this.mentors.requireApprovedMentor(caller.requireAccountId());
		UUID customer = this.posts.openCustomer(postId).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
		RatingSummary summary = this.reputation.visibleSummary(customer);
		return new RatingSummaryView(summary.average(), summary.count());
	}

}
