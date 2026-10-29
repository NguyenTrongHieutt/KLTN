/**
 * Layer 9 (AD-3, AD-33): Ratings and Portfolio Entries. The module holds the one definition of an
 * account's visible Rating summary in one role ({@link vn.vibefix.reputation.RatingRole}): as Customer, which Job
 * Posts show (FR-12), and as Mentor and as Crew member, which PUB-4's two histories show (FR-51, Story 21.3); and of
 * Portfolio Entries. J9 opens the Rating Window
 * (FR-49): the Customer and the Mentor rate each other blind, the Mentor rates each Crew member who was
 * ever active, and those Crew members may give private feedback about the Mentor, which only
 * Administrators read (FR-78). Only published Ratings count. J9 also creates the Portfolio Entries of a Job with
 * Accepted work, Closed or Cancelled (FR-50; J10 refuses a Job with Accepted work, which reaches Cancelled through
 * Closing and J9): the Mentor's, and every Crew member's who was active during Accepted work, published after each
 * person's own data-deletion confirmation.
 * <p>
 * Public API: {@link vn.vibefix.reputation.Reputation}, {@link vn.vibefix.reputation.RatingSummary},
 * {@link vn.vibefix.reputation.RatingRole},
 * {@link vn.vibefix.reputation.PortfolioEntry}, and {@link vn.vibefix.reputation.PrivateFeedback}. The
 * HTTP endpoints, the listener, and the Deadlines are wiring in {@code reputation.internal}.
 */
@ApplicationModule(allowedDependencies = { "shared", "audit", "catalog", "identity", "notification", "timers", "product",
		"job", "repo", "work", "delivery" })
package vn.vibefix.reputation;

import org.springframework.modulith.ApplicationModule;
