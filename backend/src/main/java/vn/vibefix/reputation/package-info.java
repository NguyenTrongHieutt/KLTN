/**
 * Layer 9 (AD-3, AD-33): Ratings and Portfolio Entries. The module holds the one definition of an
 * account's visible Rating summary, which Job Posts show for their Customer (FR-12) and public
 * profiles show for Mentors and Students (FR-51), and of Portfolio Entries. J9 opens the Rating Window
 * (FR-49): the Customer and the Mentor rate each other blind, the Mentor rates each Crew member who was
 * ever active, and those Crew members may give private feedback about the Mentor, which only
 * Administrators read (FR-78). Only published Ratings count. J9 also creates the Portfolio Entries of a
 * Job with Accepted work (FR-50), published after each person's own data-deletion confirmation.
 * <p>
 * Public API: {@link vn.vibefix.reputation.Reputation}, {@link vn.vibefix.reputation.RatingSummary},
 * {@link vn.vibefix.reputation.PortfolioEntry}, and {@link vn.vibefix.reputation.PrivateFeedback}. The
 * HTTP endpoints, the listener, and the Deadlines are wiring in {@code reputation.internal}.
 */
@ApplicationModule(allowedDependencies = { "shared", "audit", "catalog", "identity", "notification", "timers", "product",
		"job", "repo", "work", "delivery" })
package vn.vibefix.reputation;

import org.springframework.modulith.ApplicationModule;
