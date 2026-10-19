/**
 * Layer 9 (AD-3, AD-33): Ratings and Portfolio Entries. The module holds the one definition of an
 * account's visible Rating summary, which Job Posts show for their Customer (FR-12) and public
 * profiles show for Mentors and Students (FR-51), and of Portfolio Entries; both arrive with J9's
 * Rating Window (FR-49, FR-50), so no account has any yet.
 * <p>
 * Public API: {@link vn.vibefix.reputation.Reputation}, {@link vn.vibefix.reputation.RatingSummary},
 * and {@link vn.vibefix.reputation.PortfolioEntry}. The HTTP endpoints are wiring in
 * {@code reputation.internal}.
 */
@ApplicationModule(allowedDependencies = { "shared", "identity", "job" })
package vn.vibefix.reputation;

import org.springframework.modulith.ApplicationModule;
